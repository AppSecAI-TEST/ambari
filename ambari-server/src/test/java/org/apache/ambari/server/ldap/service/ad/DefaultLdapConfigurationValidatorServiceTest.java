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

import org.apache.ambari.server.ldap.AmbariLdapConfiguration;
import org.apache.ambari.server.ldap.LdapConfigurationValidatorService;
import org.apache.ambari.server.ldap.service.LdapConnectionService;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.directory.shared.ldap.constants.SchemaConstants;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

public class DefaultLdapConfigurationValidatorServiceTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLdapConfigurationValidatorService.class);
  private static final String TEST_USER = "einstein";

  LdapConfigurationValidatorService ldapConfigurationValidatorService = new DefaultLdapConfigurationValidatorService();


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
    // GIVEN
    Map<String, Object> ldapPropsMap = Maps.newHashMap();

    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.BIND_ANONIMOUSLY.propertyName(), "true");
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.LDAP_SERVER_HOST.propertyName(), "ldap.forumsys.com");
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.LDAP_SERVER_PORT.propertyName(), "389");
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.BASE_DN.propertyName(), "dc=example,dc=com");

    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.USER_OBJECT_CLASS.propertyName(), SchemaConstants.PERSON_OC);
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.USER_NAME_ATTRIBUTE.propertyName(), SchemaConstants.UID_AT);
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.USER_SEARCH_BASE.propertyName(), "dc=example,dc=com");


    AmbariLdapConfiguration ambariLdapConfiguration = new AmbariLdapConfiguration(ldapPropsMap);
    LdapConnectionService connectionService = new DefaultLdapConnectionService();
    LdapNetworkConnection ldapConnection = connectionService.createLdapConnection(ambariLdapConfiguration);

    ldapConfigurationValidatorService.checkUserAttributes(ldapConnection, "einstein", "", ambariLdapConfiguration);
  }

  @Test
  public void testRetrieveGorupsForuser() throws Exception {
    // GIVEN
    Map<String, Object> ldapPropsMap = Maps.newHashMap();

    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.BIND_ANONIMOUSLY.propertyName(), "true");
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.LDAP_SERVER_HOST.propertyName(), "ldap.forumsys.com");
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.LDAP_SERVER_PORT.propertyName(), "389");
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.BASE_DN.propertyName(), "dc=example,dc=com");


    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.GROUP_OBJECT_CLASS.propertyName(), SchemaConstants.GROUP_OF_UNIQUE_NAMES_OC);
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.GROUP_NAME_ATTRIBUTE.propertyName(), SchemaConstants.CN_AT);
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.GROUP_MEMBER_ATTRIBUTE.propertyName(), SchemaConstants.UNIQUE_MEMBER_AT);
    ldapPropsMap.put(AmbariLdapConfiguration.LdapConfigProperty.GROUP_SEARCH_BASE.propertyName(), "dc=example,dc=com");


    AmbariLdapConfiguration ambariLdapConfiguration = new AmbariLdapConfiguration(ldapPropsMap);
    LdapConnectionService connectionService = new DefaultLdapConnectionService();
    LdapNetworkConnection ldapConnection = connectionService.createLdapConnection(ambariLdapConfiguration);

    ldapConfigurationValidatorService.checkGroupAttributes(ldapConnection, "uid=einstein,dc=example,dc=com", ambariLdapConfiguration);
  }
}