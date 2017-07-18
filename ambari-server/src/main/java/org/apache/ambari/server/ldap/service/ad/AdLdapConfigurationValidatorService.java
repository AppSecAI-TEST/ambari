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

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ldap.AmbariLdapConfiguration;
import org.apache.ambari.server.ldap.LdapConfigurationValidatorService;
import org.apache.ambari.server.ldap.service.AmbariLdapException;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.cursor.SearchCursor;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.directory.ldap.client.api.search.FilterBuilder;
import org.apache.directory.shared.ldap.constants.SchemaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

/**
 * Implementation of the validation logic using the Apache Directory API.
 */
@Singleton
public class AdLdapConfigurationValidatorService implements LdapConfigurationValidatorService {

  private static final Logger LOGGER = LoggerFactory.getLogger(AdLdapConfigurationValidatorService.class);

  @Inject
  private LdapConfigurationConverter ldapConfigurationConverter;

  /**
   * Facilitating the instantiation
   */
  @Inject
  public AdLdapConfigurationValidatorService() {
  }

  @Override
  public void checkConnection(AmbariLdapConfiguration ambariLdapConfiguration) throws AmbariLdapException {
    try {
      LOGGER.info("Testing the connection based on the configuration: {}", ambariLdapConfiguration);

      LdapConnectionConfig connectionConfig = ldapConfigurationConverter.getLdapConnectionConfig(ambariLdapConfiguration);
      LdapNetworkConnection connection = new LdapNetworkConnection(connectionConfig);

      if (ambariLdapConfiguration.bindAnonimously()) {
        LOGGER.debug("Binding anonimously ...");
        connection.bind();
      } else {
        LOGGER.debug("Binding with manager DN and manager password ...");
        connection.bind(ambariLdapConfiguration.managerDn(), ambariLdapConfiguration.managerPassword());
      }

      if (connection.isConnected()) {
        LOGGER.info("Successfully connected to the LDAP server.");
      }

      connection.close();

    } catch (Exception e) {
      LOGGER.warn("Could not bind to the LDAP server base don the provided configuration ...");
      throw new AmbariLdapException(e);
    }
  }


  /**
   * Checks the user attributes provided in the configuration instance by issuing a search for a (known) test user in the LDAP.
   * Attributes are considered correct if there is at least one entry found.
   *
   * Invalid attributes are signaled by throwing an exception.
   *
   * @param username                the username
   * @param password                the password
   * @param ambariLdapConfiguration configuration instance holding ldap configuration details
   * @throws AmbariException if the attributes are not valid or any errors occurs
   */
  @Override
  public void checkUserAttributes(String username, String password, AmbariLdapConfiguration ambariLdapConfiguration) throws AmbariException {
    LdapNetworkConnection connection = null;
    SearchCursor searchCursor = null;
    try {
      LOGGER.info("Checking user attributes for user {} r ...", username);

      LdapConnectionConfig connectionConfig = ldapConfigurationConverter.getLdapConnectionConfig(ambariLdapConfiguration);
      connection = new LdapNetworkConnection(connectionConfig);


      if (!ambariLdapConfiguration.bindAnonimously()) {
        LOGGER.debug("Anonimous binding not supported, binding with the manager detailas...");
        connection.bind(ambariLdapConfiguration.managerDn(), ambariLdapConfiguration.managerPassword());
      } else {
        LOGGER.debug("Binding anonimously ...");
        connection.bind();
      }

      if (!connection.isConnected()) {
        LOGGER.error("Not connected to the LDAP server. Connection instance: {}", connection);
        throw new IllegalStateException("The connection to the LDAP server is not alive");
      }

      // set up a filter based on the provided attributes
      String filter = FilterBuilder.and(
        FilterBuilder.equal(SchemaConstants.OBJECT_CLASS_AT, ambariLdapConfiguration.userObjectClass()),
        FilterBuilder.equal(ambariLdapConfiguration.userNameAttribute(), username))
        .toString();

      LOGGER.info("Searching for the user: {} using the search filter: {}", username, filter);
      EntryCursor entryCursor = connection.search(new Dn(ambariLdapConfiguration.baseDn()), filter, SearchScope.SUBTREE);

      // collecting search result entries
      List<Entry> users = Lists.newArrayList();
      for (Entry entry : entryCursor) {
        users.add(entry);
      }

      // there should be at least one user found
      if (users.isEmpty()) {
        String msg = String.format("There are no users found using the filter: [ %s ]. Try changing the attribute values", filter);
        LOGGER.error(msg);
        throw new Exception(msg);
      }

      LOGGER.info("Attibute validation succeeded. Filter: {}", filter);

    } catch (Exception e) {

      LOGGER.error("Error while checking user attributes.");
      throw new AmbariException("Error while checking user attributes", e);

    } finally {

      LOGGER.debug("Closing the connection and searchresult ...");

      if (null != searchCursor) {
        searchCursor.close();
      }

      if (null != connection) {
        try {
          connection.close();
        } catch (IOException e) {
          LOGGER.error("Exception occurred while closing the connection", e);
        }
      }

    }
  }

  @Override
  public void checkGroupAttributes(AmbariLdapConfiguration configuration) throws AmbariException {

  }


}
