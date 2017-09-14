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
package wherehows.dao.table;

import java.util.Collections;
import java.util.List;
import javax.persistence.EntityManagerFactory;
import wherehows.models.table.DictFieldDetail;


public class FieldDetailDao extends BaseDao {

  private static final String DELETE_BY_DATASET_ID = "DELETE FROM dict_field_detail WHERE dataset_id = :datasetId";

  public FieldDetailDao(EntityManagerFactory factory) {
    super(factory);
  }

  public List<DictFieldDetail> findById(int datasetId) {
    return findListBy(DictFieldDetail.class, "dataset_id", datasetId);
  }

  public void deleteByDatasetId(int datasetId) {
    executeUpdate(DELETE_BY_DATASET_ID, Collections.singletonMap("datasetId", datasetId));
  }
}
