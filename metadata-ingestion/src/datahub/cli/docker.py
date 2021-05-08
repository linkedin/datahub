import datetime
import pathlib
import subprocess
import sys
import tempfile
import time
from typing import List, NoReturn, Optional

import click
import requests

from datahub.cli.docker_check import check_local_docker_containers
from datahub.ingestion.run.pipeline import Pipeline

SIMPLE_QUICKSTART_COMPOSE_FILE = "docker/cli/docker-compose.quickstart.yml"
BOOTSTRAP_MCES_FILE = "metadata-ingestion/examples/mce_files/bootstrap_mce.json"

GITHUB_BASE_URL = "https://raw.githubusercontent.com/linkedin/datahub/master"
GITHUB_QUICKSTART_COMPOSE_URL = f"{GITHUB_BASE_URL}/{SIMPLE_QUICKSTART_COMPOSE_FILE}"
GITHUB_BOOTSTRAP_MCES_URL = f"{GITHUB_BASE_URL}/{BOOTSTRAP_MCES_FILE}"


@click.group()
def docker() -> None:
    pass


def _print_issue_list_and_exit(
    issues: List[str], header: str, footer: Optional[str] = None
) -> NoReturn:
    click.secho(header, fg="bright_red")
    for issue in issues:
        click.echo(f"- {issue}")
    if footer:
        click.echo()
        click.echo(footer)
    sys.exit(1)


def docker_check_impl() -> None:
    issues = check_local_docker_containers()
    if not issues:
        click.secho("✔ No issues detected", fg="green")
    else:
        _print_issue_list_and_exit(issues, "The following issues were detected:")


@docker.command()
def check() -> None:
    """Check that the Docker containers are healthy"""
    docker_check_impl()


@docker.command()
@click.option(
    "--use-locally-generated-quickstart",
    type=bool,
    is_flag=True,
    default=False,
    help=f"Use the local {SIMPLE_QUICKSTART_COMPOSE_FILE} instead of pulling from GitHub",
)
def quickstart(use_locally_generated_quickstart: bool) -> None:
    # Run pre-flight checks.
    issues = check_local_docker_containers(preflight_only=True)
    if issues:
        _print_issue_list_and_exit(issues, "Unable to run quickstart:")

    if use_locally_generated_quickstart:
        path = (
            pathlib.Path(__file__) / f"../../../../../{SIMPLE_QUICKSTART_COMPOSE_FILE}"
        ).resolve()
    else:
        with tempfile.NamedTemporaryFile(suffix=".yml", delete=False) as tmp_file:
            path = pathlib.Path(tmp_file.name)

            # Download the quickstart docker-compose file from GitHub.
            quickstart_download_response = requests.get(GITHUB_QUICKSTART_COMPOSE_URL)
            quickstart_download_response.raise_for_status()
            tmp_file.write(quickstart_download_response.content)

    base_command = ["docker-compose", "-f", f"{path}", "-p", "datahub"]

    # Pull the latest containers.
    subprocess.run(base_command + ["pull"], check=True)

    max_wait_time = datetime.timedelta(minutes=5)
    start_time = datetime.datetime.now()
    sleep_interval = datetime.timedelta(seconds=2)
    up_interval = datetime.timedelta(minutes=1)
    up_attempts = 0
    while (datetime.datetime.now() - start_time) < max_wait_time:
        # Attempt to run docker-compose up every minute.
        if (datetime.datetime.now() - start_time) > up_attempts * up_interval:
            click.echo()
            subprocess.run(base_command + ["up", "-d"])
            up_attempts += 1

        # Check docker health every few seconds.
        issues = check_local_docker_containers()
        if not issues:
            break

        # Wait until next iteration.
        click.echo(".", nl=False)
        time.sleep(sleep_interval.total_seconds())
    else:
        # Falls through if the while loop doesn't exit via break.
        click.echo()
        with tempfile.NamedTemporaryFile(suffix=".log", delete=False) as log_file:
            ret = subprocess.run(
                base_command + ["logs"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=True,
            )
            log_file.write(ret.stdout)

        _print_issue_list_and_exit(
            issues,
            header="Unable to run quickstart - the following issues were detected:",
            footer="If you think something went wrong, please file an issue at https://github.com/linkedin/datahub/issues\n"
            "or send a message in our Slack https://slack.datahubproject.io/\n"
            f"Be sure to attach the logs from {log_file.name}",
        )

    # Handle success condition.
    click.echo()
    click.secho("✔ DataHub is now running", fg="green")
    click.secho(
        "Ingest some demo data using `datahub docker ingest-sample-data`,\n"
        "or head to http://localhost:9002 to play around with the frontend.",
        fg="green",
    )


@docker.command()
@click.option(
    "--path",
    type=click.Path(exists=True, dir_okay=False),
    help=f"The MCE json file to ingest. Defaults to downloading {BOOTSTRAP_MCES_FILE} from GitHub",
)
def ingest_sample_data(path: Optional[str]) -> None:
    if path is None:
        click.echo("Downloading sample data...")
        with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as tmp_file:
            path = str(pathlib.Path(tmp_file.name))

            # Download the bootstrap MCE file from GitHub.
            mce_json_download_response = requests.get(GITHUB_BOOTSTRAP_MCES_URL)
            mce_json_download_response.raise_for_status()
            tmp_file.write(mce_json_download_response.content)
        click.echo(f"Downloaded to {path}")

    # Verify that docker is up.
    issues = check_local_docker_containers()
    if issues:
        _print_issue_list_and_exit(
            issues,
            header="Docker is not ready:",
            footer="Try running `datahub docker quickstart` first",
        )

    # Run ingestion.
    click.echo("Starting ingestion...")
    pipeline = Pipeline.create(
        {
            "source": {
                "type": "file",
                "config": {
                    "filename": path,
                },
            },
            "sink": {
                "type": "datahub-rest",
                "config": {"server": "http://localhost:8080"},
            },
        }
    )
    pipeline.run()
    ret = pipeline.pretty_print_summary()
    sys.exit(ret)


# TODO add a clean/nuke command
