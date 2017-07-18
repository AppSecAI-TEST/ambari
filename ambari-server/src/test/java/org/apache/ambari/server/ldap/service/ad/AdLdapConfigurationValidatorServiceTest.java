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

import static org.junit.Assert.assertNotNull;

import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ldap.AmbariLdapConfiguration;
import org.apache.ambari.server.ldap.LdapConfigurationValidatorService;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.cursor.SearchCursor;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.message.Response;
import org.apache.directory.api.ldap.model.message.SearchRequest;
import org.apache.directory.api.ldap.model.message.SearchRequestImpl;
import org.apache.directory.api.ldap.model.message.SearchResultEntry;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.directory.ldap.client.api.search.FilterBuilder;
import org.apache.directory.shared.ldap.constants.SchemaConstants;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

public class AdLdapConfigurationValidatorServiceTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(AdLdapConfigurationValidatorService.class);
  private static final String TEST_USER = "Jocika10";

  LdapConfigurationValidatorService ldapConfigurationValidatorService = new AdLdapConfigurationValidatorService();


  @Test
  public void testCheckAttributes() throws Exception {

    // WHEN
    LdapConnectionConfig config = new LdapConnectionConfig();
    config.setLdapHost("localhost");
    config.setLdapPort(389);
    LdapConnection connection = new LdapNetworkConnection(config);

    // THEN
    connection.anonymousBind();


    EntryCursor cursor = connection.search("dc=dev,dc=local", "(objectclass=*)", SearchScope.ONELEVEL);

    for (Entry entry : cursor) {
      assertNotNull(entry);
      System.out.println(entry);
    }

    cursor.close();

  }

  @Test
  public void testCheckUserAttributes() throws Exception {
    Map<String, Object> ldapPropsMap = Maps.newHashMap();

    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.BIND_ANONIMOUSLY.propertyName(), true);
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.LDAP_SERVER_HOST.propertyName(), "localhost");
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.LDAP_SERVER_PORT.propertyName(), "389");
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.BASE_DN.propertyName(), "dc=dev,dc=local");
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.USER_OBJECT_CLASS.propertyName(), SchemaConstants.PERSON_OC);
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.USER_NAME_ATTRIBUTE.propertyName(), SchemaConstants.UID_AT);

    AmbariLdapConfiguration ambariLdapConfiguration = new AmbariLdapConfiguration(ldapPropsMap);


    try {
      LOGGER.info("Authenticating user {} against the LDAP server ...", TEST_USER);
      LdapConfigurationConverter ldapConfigurationConverter = new LdapConfigurationConverter();

      LdapConnectionConfig connectionConfig = ldapConfigurationConverter.getLdapConnectionConfig(ambariLdapConfiguration);
      LdapNetworkConnection connection = new LdapNetworkConnection(connectionConfig);

      String filter = FilterBuilder.and(
        FilterBuilder.equal(SchemaConstants.OBJECT_CLASS_AT, ambariLdapConfiguration.userObjectClass()),
        FilterBuilder.equal(ambariLdapConfiguration.userNameAttribute(), TEST_USER))
        .toString();

      SearchRequest searchRequest = new SearchRequestImpl();
      searchRequest.setBase(new Dn(ambariLdapConfiguration.baseDn()));
      searchRequest.setFilter(filter);
      searchRequest.setScope(SearchScope.SUBTREE);

      LOGGER.info("loking up user: {} based on the filtr: {}", TEST_USER, filter);

      connection.bind();
      SearchCursor searchCursor = connection.search(searchRequest);

      while (searchCursor.next()) {
        Response response = searchCursor.get();

        // process the SearchResultEntry
        if (response instanceof SearchResultEntry) {
          Entry resultEntry = ((SearchResultEntry) response).getEntry();
          System.out.println(resultEntry);
        }
      }

      searchCursor.close();

    } catch (Exception e) {
      throw new AmbariException("Error during user authentication check", e);
    }

  }

}