/**
 * Copyright 2013 Cloudera Inc.
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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.cdk.data;

/**
 * <p>
 * Exception thrown to indicate that there was a problem
 * parsing or validating a schema.
 * <p>
 * @since 0.9.0
 */
public class SchemaValidationException extends DatasetException {
  
  public SchemaValidationException(String msg) {
    super(msg);
  }

  public SchemaValidationException(Throwable cause) {
    super(cause);
  }

  public SchemaValidationException(String msg, Throwable cause) {
    super(msg, cause);
  }
}
