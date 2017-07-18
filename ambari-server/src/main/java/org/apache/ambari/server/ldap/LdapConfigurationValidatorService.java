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

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ldap.service.AmbariLdapException;

/**
 * Collection of operations for validating ldap configuration.
 * It's intended to decouple implementations using different libraries.
 */
public interface LdapConfigurationValidatorService {

  /**
   * Tests the connection based on the provided configuration.
   *
   * @param configuration the ambari ldap configuration instance
   * @throws AmbariLdapException if the connection is not possible
   */
  void checkConnection(AmbariLdapConfiguration configuration) throws AmbariLdapException;

  /**
   * Checks whether the group related LDAP attributes in the configuration are correct.
   *
   * @param configuration the configuration instance holding the available properties
   * @throws AmbariException if the attributes are not valid
   */
  void checkGroupAttributes(AmbariLdapConfiguration configuration) throws AmbariException;

  /**
   * Tries to connect to the LDAP server with the given credentials.
   * Primarily used for testing the user before performing other operations (eg. attribute detection)s
   *
   * @param username      the username
   * @param password      the password
   * @param configuration the available ldap configuration
   * @throws AmbariException if the connection couldn't be estabilished
   */
  void checkUserAttributes(String username, String password, AmbariLdapConfiguration configuration) throws AmbariException;
}
