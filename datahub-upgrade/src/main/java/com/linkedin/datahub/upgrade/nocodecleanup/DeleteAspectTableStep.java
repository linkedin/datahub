package com.linkedin.datahub.upgrade.nocodecleanup;

import com.linkedin.datahub.upgrade.UpgradeContext;
import com.linkedin.datahub.upgrade.UpgradeStep;
import com.linkedin.datahub.upgrade.UpgradeStepResult;
import com.linkedin.datahub.upgrade.impl.DefaultUpgradeStepResult;
import io.ebean.EbeanServer;
import java.util.function.Function;


// Do we need SQL-tech specific migration paths?
public class DeleteAspectTableStep implements UpgradeStep<Void> {

  private final EbeanServer _server;

  public DeleteAspectTableStep(final EbeanServer server) {
    _server = server;
  }

  @Override
  public String id() {
    return "DeleteLegacyAspectRowsStep";
  }

  @Override
  public int retryCount() {
    return 1;
  }

  @Override
  public Function<UpgradeContext, UpgradeStepResult<Void>> executable() {
    return (context) -> {
      try {
        _server.execute(_server.createSqlUpdate("DELETE FROM metadata_aspect;"));
      } catch (Exception e) {
        return new DefaultUpgradeStepResult<>(
            id(),
            UpgradeStepResult.Result.FAILED,
            String.format("Failed to delete data from legacy table metadata_aspect: %s", e.toString()));
      }
      return new DefaultUpgradeStepResult<>(id(), UpgradeStepResult.Result.SUCCEEDED);
    };
  }
}
