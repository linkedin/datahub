package com.linkedin.datahub.upgrade.nocode;

import com.linkedin.datahub.upgrade.impl.DefaultUpgradeStepResult;
import com.linkedin.datahub.upgrade.UpgradeContext;
import com.linkedin.datahub.upgrade.UpgradeStep;
import com.linkedin.datahub.upgrade.UpgradeStepResult;
import com.linkedin.metadata.entity.AspectStorageValidationUtil;
import io.ebean.EbeanServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.function.Function;

public class UpgradeQualificationStep implements UpgradeStep<Void> {

  private static String convertStreamToString(InputStream is) {

    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
    StringBuilder sb = new StringBuilder();

    String line = null;
    try {
      while ((line = reader.readLine()) != null) {
        sb.append(line + "\n");
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      try {
        is.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return sb.toString();
  }

  private final EbeanServer _server;

  UpgradeQualificationStep(EbeanServer server) {
    _server = server;
  }

  @Override
  public String id() {
    return "UpgradeQualificationStep";
  }

  @Override
  public int retryCount() {
    return 2;
  }

  @Override
  public Function<UpgradeContext, UpgradeStepResult<Void>> executable() {
    return (context) -> {

      if (context.parsedArgs().containsKey(NoCodeUpgrade.FORCE_UPGRADE_ARG_NAME)) {
        return new DefaultUpgradeStepResult<>(
            id(),
            UpgradeStepResult.Result.SUCCEEDED,
            "Forced upgrade detected. Proceeding with upgrade...");
      }

      try {
        if (isQualified(_server)) {
          // Qualified.
          return new DefaultUpgradeStepResult<>(
              id(),
              UpgradeStepResult.Result.SUCCEEDED,
              "Found qualified upgrade candidate. Proceeding with upgrade...");
        }
        // Unqualified (Table already exists)
        return new DefaultUpgradeStepResult<>(
            id(),
            UpgradeStepResult.Result.SUCCEEDED,
            UpgradeStepResult.Action.ABORT,
            "Failed to qualify upgrade candidate. Aborting the upgrade...");
      } catch (Exception e) {
        return new DefaultUpgradeStepResult<>(
            id(),
            UpgradeStepResult.Result.FAILED,
            String.format("Failed to check if metadata_aspect_v2 table exists: %s", e.toString()));
      }
    };
  }

  private boolean isQualified(EbeanServer server) {

    return AspectStorageValidationUtil.checkV1TableExists(server)
        && (
            !AspectStorageValidationUtil.checkV2TableExists(server)
                || AspectStorageValidationUtil.getV2RowCount(server) == 0
           );
  }
}
