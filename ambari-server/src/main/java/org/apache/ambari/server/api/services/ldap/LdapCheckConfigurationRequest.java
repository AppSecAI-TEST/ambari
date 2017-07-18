/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.api.services.ldap;


import com.google.gson.annotations.SerializedName;

public class LdapCheckConfigurationRequest implements LdapOperationRequest {

  @SerializedName("AmbariConfiguration")
  private AmbariConfigurationDTO ambariConfiguration;

  @SerializedName("RequestInfo")
  private LdapRequestInfo requestInfo;

  public LdapCheckConfigurationRequest() {
  }


  public AmbariConfigurationDTO getAmbariConfiguration() {
    return ambariConfiguration;
  }

  public void setAmbariConfiguration(AmbariConfigurationDTO ambariConfiguration) {
    this.ambariConfiguration = ambariConfiguration;
  }

  public LdapRequestInfo getRequestInfo() {
    return requestInfo;
  }

  public void setRequestInfo(LdapRequestInfo requestInfo) {
    this.requestInfo = requestInfo;
  }
}
