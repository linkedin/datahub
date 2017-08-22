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
package wherehows.models;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;
import javax.persistence.Transient;
import lombok.Data;


@Data
@Entity
@IdClass(value = DatasetOwner.DatasetOwnerKeys.class)
@Table(name = "dataset_owner")
public class DatasetOwner {

  @Id
  @Column(name = "owner_id")
  private String userName;

  @Transient
  private String email;

  @Transient
  private String name;

  @Column(name = "is_group")
  private Boolean isGroup;

  @Column(name = "is_active")
  private Boolean isActive;

  @Column(name = "owner_id_type")
  private String idType;

  @Id
  @Column(name = "owner_source")
  private String source;

  @Column(name = "namespace")
  private String namespace;

  @Column(name = "owner_type")
  private String type;

  @Column(name = "owner_sub_type")
  private String subType;

  @Column(name = "sort_id")
  private Integer sortId;

  @Column(name = "confirmed_by")
  private String confirmedBy;

  @Column(name = "modified_time")
  private Long modifiedTime;

  static class DatasetOwnerKeys implements Serializable {
    private String userName;
    private String source;
  }
}
