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

import org.apache.ambari.server.ldap.AmbariLdapConfiguration;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;

/**
 * Contract defining factory methods for creating LDAP connection instances.
 * Implementers contain the logic of creating different connection instances and the afferent boilerplate code.
 */
public interface LdapConnectionService {

  /**
   * Creates an LdapConnection instance based on the provided configuration
   *
   * @param ambariLdapConfiguration configuration instance with information for creating the connection instance
   * @return a set up LdapConnection instance
   */
  LdapNetworkConnection createLdapConnection(AmbariLdapConfiguration ambariLdapConfiguration);


}
