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

import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class LDAPServiceTest {

  private static String JSON_STRING = "{\n" +
    "  \"AmbariConfiguration\": {\n" +
    "    \"type\": \"ldap-config\",\n" +
    "    \"data\": [{\n" +
    "        \"authentication.ldap.primaryUrl\": \"localhost:33389\",\n" +
    "        \"authentication.ldap.secondaryUrl\": \"localhost:333\",\n" +
    "        \"authentication.ldap.baseDn\": \"dc=ambari,dc=apache,dc=org\"\n" +
    "      }]\n" +
    "  }\n" +
    "}";

  @Test
  public void testJaxRsJsonTransformation() throws Exception {
    // GIVEN
    ObjectMapper objectMapper = new ObjectMapper();

    Gson gsonJsonProvider = new GsonBuilder().create();


    // WHEN
    LdapCheckConfigurationRequest ldapCheckConfigurationRequest = gsonJsonProvider.fromJson(JSON_STRING, LdapCheckConfigurationRequest.class);
    // LdapCheckConfigurationRequest ldapCheckConfigurationRequest = objectMapper.readValue(JSON_STRING, LdapCheckConfigurationRequest.class);

    // THEN
    Assert.assertNotNull(ldapCheckConfigurationRequest);

  }


  @Test
  public void testLdapConnection() throws Exception {
    // GIVEN
    LdapConnection connection = new LdapNetworkConnection("localhost", 389);

    // WHEN
    connection.bind();
    // THEN

  }


  @Test
  public void testLdapConnectionConfigs() throws Exception {
    // GIVEN
    LdapConnectionConfig config = new LdapConnectionConfig();
    config.setLdapHost("localhost");
    config.setLdapPort(389);

    // WHEN
    LdapConnection connection = new LdapNetworkConnection(config);

    // THEN
    connection.anonymousBind();

    Assert.assertNotNull(connection);
  }
}