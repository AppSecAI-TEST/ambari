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


package org.apache.ambari.server.ldap.service;

import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.ambari.server.ldap.AmbariLdapConfiguration;
import org.apache.ambari.server.ldap.LdapConfigurationValidatorService;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class AmbariLdapFacade implements LdapFacade {

  private static final Logger LOGGER = LoggerFactory.getLogger(AmbariLdapFacade.class);

  private enum Parameters {
    TEST_USER_NAME("ldap.test.user.name"),
    TEST_USER_PASSWORD("ldap.test.user.password");

    private String parameterKey;

    Parameters(String parameterKey) {
      this.parameterKey = parameterKey;
    }

    private String getParameterKey() {
      return parameterKey;
    }

  }

  @Inject
  private LdapConfigurationValidatorService ldapConfigurationValidatorService;

  @Inject
  private LdapConnectionService ldapConnectionService;

  @Inject
  public AmbariLdapFacade() {
  }

  @Override
  public void checkConnection(AmbariLdapConfiguration ambariLdapConfiguration) throws AmbariLdapException {
    try {
      LOGGER.info("Validating LDAP connection related configuration based on: {}", ambariLdapConfiguration);
      LdapConnection connection = ldapConnectionService.createLdapConnection(ambariLdapConfiguration);
      ldapConfigurationValidatorService.checkConnection(connection, ambariLdapConfiguration);
    } catch (AmbariLdapException e) {
      LOGGER.error("Validating LDAP connection configuration failed", e);
      throw e;
    }
    LOGGER.info("Validating LDAP connection related configuration: SUCCESS");
  }


  @Override
  public void detectAttributes(AmbariLdapConfiguration ambariLdapConfiguration) {
    LOGGER.info("Detecting LDAP configuration attributes ...");
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public void checkLdapAttibutes(Map<String, Object> parameters, AmbariLdapConfiguration ldapConfiguration) throws AmbariLdapException {
    String userName = getTestUserNameFromParameters(parameters);
    String testUserPass = getTestUserPasswordFromParameters(parameters);

    if (null == userName) {
      throw new IllegalArgumentException("No test user available for testing LDAP attributes");
    }

    LdapConnection ldapConnection = ldapConnectionService.createLdapConnection(ldapConfiguration);

    LOGGER.info("Testing LDAP user attributes with test user: {}", userName);
    String userDn = ldapConfigurationValidatorService.checkUserAttributes(ldapConnection, userName, testUserPass, ldapConfiguration);

    LOGGER.info("Testing LDAP group attributes with test user dn: {}", userDn);
    Set<String> groups = ldapConfigurationValidatorService.checkGroupAttributes(ldapConnection, userDn, ldapConfiguration);

  }


  private String getTestUserNameFromParameters(Map<String, Object> parameters) {
    return (String) parameterValue(parameters, Parameters.TEST_USER_NAME);
  }

  private String getTestUserPasswordFromParameters(Map<String, Object> parameters) {
    return (String) parameterValue(parameters, Parameters.TEST_USER_PASSWORD);
  }

  private Object parameterValue(Map<String, Object> parameters, Parameters parameter) {
    Object value = null;
    if (parameters.containsKey(parameter.getParameterKey())) {
      value = parameters.get(parameter.getParameterKey());
    } else {
      LOGGER.warn("Parameter [{}] is missing from parameters", parameter.getParameterKey());
    }
    return value;
  }
}
