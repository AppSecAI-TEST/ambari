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

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.ambari.annotations.ApiIgnore;
import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.api.services.BaseService;
import org.apache.ambari.server.api.services.Result;
import org.apache.ambari.server.api.services.ResultImpl;
import org.apache.ambari.server.api.services.ResultStatus;
import org.apache.ambari.server.ldap.AmbariLdapConfiguration;
import org.apache.ambari.server.ldap.LdapConfigurationFactory;
import org.apache.ambari.server.ldap.service.LdapFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Endpoint designated to LDAP specific operations.
 */
@StaticallyInject
@Path("/ldap")
public class LdapRestService extends BaseService {

  private static final Logger LOGGER = LoggerFactory.getLogger(LdapRestService.class);

  @Inject
  private static LdapFacade ldapFacade;

  @Inject
  private static LdapConfigurationFactory ldapConfigurationFactory;

  @POST
  @ApiIgnore // until documented
  @Path("/action") // todo this needs to be moved under the resource
  @Consumes(MediaType.APPLICATION_JSON)
  public Response validateConfiguration(LdapCheckConfigurationRequest ldapCheckConfigurationRequest) {

    Result result = new ResultImpl(new ResultStatus(ResultStatus.STATUS.OK));
    try {

      validateRequest(ldapCheckConfigurationRequest);

      AmbariLdapConfiguration ambariLdapConfiguration = ldapConfigurationFactory.createLdapConfiguration(
        ldapCheckConfigurationRequest.getAmbariConfiguration().getData().iterator().next());

      switch (ldapCheckConfigurationRequest.getRequestInfo().getAction()) {
        case "test-connection":

          LOGGER.info("Testing connection to the LDAP server ...");
          ldapFacade.checkConnection(ambariLdapConfiguration);

          break;
        case "test-attributes":

          LOGGER.info("Testing LDAP attributes ....");
          ldapFacade.checkLdapAttibutes(ldapCheckConfigurationRequest.getRequestInfo().getParameters(), ambariLdapConfiguration);

          break;
        case "detect-attributes":

          LOGGER.info("Detecting LDAP attributes ...");
          ldapFacade.detectAttributes(ambariLdapConfiguration);

          break;
        default:
          LOGGER.warn("No action provided ...");
          throw new IllegalArgumentException("No request action provided");
      }

    } catch (Exception e) {
      result = new ResultImpl(new ResultStatus(ResultStatus.STATUS.BAD_REQUEST, e));
    }

    return Response.status(result.getStatus().getStatusCode()).entity(getResultSerializer().serialize(result)).build();
  }

  private void validateRequest(LdapCheckConfigurationRequest ldapCheckConfigurationRequest) {
    String errMsg;

    if (null == ldapCheckConfigurationRequest) {
      errMsg = "No ldap configuraiton request provided";
      LOGGER.error(errMsg);
      throw new IllegalArgumentException(errMsg);
    }

    if (null == ldapCheckConfigurationRequest.getRequestInfo()) {
      errMsg = String.format("No request information provided. Request: [%s]", ldapCheckConfigurationRequest);
      LOGGER.error(errMsg);
      throw new IllegalArgumentException(errMsg);
    }

    if (null == ldapCheckConfigurationRequest.getAmbariConfiguration()
      || ldapCheckConfigurationRequest.getAmbariConfiguration().getData().size() != 1) {
      errMsg = String.format("No / Invalid configuration data provided. Request: [%s]", ldapCheckConfigurationRequest);
      LOGGER.error(errMsg);
      throw new IllegalArgumentException(errMsg);
    }
  }
}
