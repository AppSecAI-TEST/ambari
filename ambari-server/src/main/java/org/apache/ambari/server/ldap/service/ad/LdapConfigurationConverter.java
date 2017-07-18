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

package org.apache.ambari.server.ldap.service.ad;

import javax.inject.Singleton;

import org.apache.ambari.server.ldap.AmbariLdapConfiguration;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts between ambari specific ldap types and the 3rd party ldap library
 */
@Singleton
public class LdapConfigurationConverter {

  private static final Logger LOGGER = LoggerFactory.getLogger(LdapConfigurationConverter.class);

  /**
   * Creates a {@link LdapConnectionConfig} instance based on the provided ambari specific configurations
   *
   * @param ambariAmbariLdapConfiguration
   * @return
   */
  public LdapConnectionConfig getLdapConnectionConfig(AmbariLdapConfiguration ambariAmbariLdapConfiguration) {
    LOGGER.debug("Creating a configuration instance based on the ambari configuration: {}", ambariAmbariLdapConfiguration);

    LdapConnectionConfig ldapConnectionConfig = new LdapConnectionConfig();
    ldapConnectionConfig.setLdapHost(ambariAmbariLdapConfiguration.ldapServerHost());
    ldapConnectionConfig.setLdapPort(ambariAmbariLdapConfiguration.ldapServerPort());
    ldapConnectionConfig.setUseSsl(ambariAmbariLdapConfiguration.useSSL());

    //todo set the other values as required
    return ldapConnectionConfig;
  }

}
