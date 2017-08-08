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

package org.apache.ambari.server.ldap;

import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ldap.service.AmbariLdapException;
import org.apache.directory.ldap.client.api.LdapConnection;

/**
 * Collection of operations for validating ldap configuration.
 * It's intended to decouple implementations using different libraries.
 */
public interface LdapConfigurationValidatorService {

  /**
   * Tests the connection based on the provided configuration.
   *
   * @param ldapConnection connection instance
   * @param configuration  the ambari ldap configuration instance
   * @throws AmbariLdapException if the connection is not possible
   */
  void checkConnection(LdapConnection ldapConnection, AmbariLdapConfiguration configuration) throws AmbariLdapException;


  /**
   * Implements LDAP user related configuration settings validation logic.
   * Implementers communicate with the LDAP server (search, bind) to validate attributes in the provided configuration
   * instance
   *
   * @param ldapConnection connection instance used to connect to the LDAP server
   * @param testUserName   the test username
   * @param testPassword   the test password
   * @param configuration  the available ldap configuration
   * @return The DN of the found user entry
   * @throws AmbariException if the connection couldn't be estabilisheds
   */
  String checkUserAttributes(LdapConnection ldapConnection, String testUserName, String testPassword, AmbariLdapConfiguration configuration) throws AmbariLdapException;

  /**
   * Checks whether the group related LDAP attributes in the configuration are correct.
   *
   * @throws AmbariException if the attributes are not valid
   */
  Set<String> checkGroupAttributes(LdapConnection ldapConnection, String userDn, AmbariLdapConfiguration ambariLdapConfiguration) throws AmbariLdapException;

}
