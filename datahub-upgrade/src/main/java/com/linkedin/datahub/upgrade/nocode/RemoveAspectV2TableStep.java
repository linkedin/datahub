package com.linkedin.datahub.upgrade.nocode;

import com.linkedin.datahub.upgrade.UpgradeContext;
import com.linkedin.datahub.upgrade.UpgradeStep;
import com.linkedin.datahub.upgrade.UpgradeStepResult;
import com.linkedin.datahub.upgrade.impl.DefaultUpgradeStepResult;
import io.ebean.EbeanServer;
import java.util.function.Function;


/**
 * Optional step for removing Aspect V2 table.
 */
public class RemoveAspectV2TableStep implements UpgradeStep<Void> {

  private final EbeanServer _server;

  public RemoveAspectV2TableStep(final EbeanServer server) {
    _server = server;
  }

  @Override
  public String id() {
    return "RemoveAspectV2TableStep";
  }

  @Override
  public Function<UpgradeContext, UpgradeStepResult<Void>> executable() {
    return (context) -> {
      if (context.args().contains("clean")) {
        context.report().addLine("Cleanup requested. Dropping metadata_aspect_v2");
        _server.execute(_server.createSqlUpdate("DROP TABLE IF EXISTS metadata_aspect_v2"));
      }
      return new DefaultUpgradeStepResult<>(id(), UpgradeStepResult.Result.SUCCEEDED);
    };
  }
}