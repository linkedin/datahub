/**
 * Copyright 2015 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package metadata.etl.treebuilder;

import java.io.InputStream;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import metadata.etl.EtlJob;

@Slf4j
public class DatasetTreeBuildETL extends EtlJob {

  public DatasetTreeBuildETL(long whExecId, Properties properties) {
    super(whExecId, properties);
  }

  @Override
  public void extract()
    throws Exception {
    log.info("In DatasetTreeBuildETL java launch extract jython scripts");
  }

  @Override
  public void transform()
    throws Exception {
    log.info("In DatasetTreeBuildETL java launch transform jython scripts");
  }

  @Override
  public void load()
    throws Exception {
    log.info("In DatasetTreeBuildETL java launch load jython scripts");
    InputStream inputStream = classLoader.getResourceAsStream("jython/DatasetTreeBuilder.py");
    interpreter.execfile(inputStream);
    inputStream.close();
    log.info("In DatasetTreeBuildETL java load jython scripts finished");
  }
}
