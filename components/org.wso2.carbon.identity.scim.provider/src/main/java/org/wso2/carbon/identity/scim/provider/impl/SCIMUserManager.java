/*
 * Copyright (c) 2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.scim.provider.impl;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.ProvisioningServiceProviderType;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.model.ThreadLocalProvisioningServiceProvider;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationManagementUtil;
import org.wso2.carbon.identity.application.mgt.ApplicationConstants;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.provisioning.IdentityProvisioningException;
import org.wso2.carbon.identity.provisioning.OutboundProvisioningManager;
import org.wso2.carbon.identity.provisioning.ProvisioningEntity;
import org.wso2.carbon.identity.provisioning.ProvisioningOperation;
import org.wso2.carbon.identity.provisioning.listener.DefaultInboundUserProvisioningListener;
import org.wso2.carbon.identity.scim.common.group.SCIMGroupHandler;
import org.wso2.carbon.identity.scim.common.utils.AttributeMapper;
import org.wso2.carbon.identity.scim.common.utils.IdentitySCIMException;
import org.wso2.carbon.identity.scim.common.utils.SCIMCommonConstants;
import org.wso2.carbon.identity.scim.common.utils.SCIMCommonUtils;
import org.wso2.carbon.identity.scim.provider.util.SCIMProviderConstants;
import org.wso2.carbon.user.api.ClaimMapping;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.claim.ClaimManager;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;
import org.wso2.charon.core.attributes.Attribute;
import org.wso2.charon.core.attributes.MultiValuedAttribute;
import org.wso2.charon.core.attributes.SimpleAttribute;
import org.wso2.charon.core.exceptions.CharonException;
import org.wso2.charon.core.exceptions.DuplicateResourceException;
import org.wso2.charon.core.exceptions.NotFoundException;
import org.wso2.charon.core.extensions.UserManager;
import org.wso2.charon.core.objects.Group;
import org.wso2.charon.core.objects.SCIMObject;
import org.wso2.charon.core.objects.User;
import org.wso2.charon.core.schema.SCIMConstants;
import org.wso2.charon.core.util.AttributeUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SCIMUserManager implements UserManager {

    public static final String USER_NAME_STRING = "userName";
    public static final String SCIM_ENABLED =  "SCIMEnabled";
    public static final String APPLICATION_DOMAIN = "Application";
    public static final String INTERNAL_DOMAIN = "Internal";
    private static final Log log = LogFactory.getLog(SCIMUserManager.class);
    private static final String USER_ID_CLAIM_URI = "http://wso2.org/claims/userid";
    private AbstractUserStoreManager carbonUM = null;
    private ClaimManager carbonClaimManager = null;
    private String consumerName;
    private boolean isBulkUserAdd = false;

    public SCIMUserManager(UserStoreManager carbonUserStoreManager, String userName,
                           ClaimManager claimManager) {

        carbonUM = (AbstractUserStoreManager) carbonUserStoreManager;
        consumerName = userName;
        carbonClaimManager = claimManager;
    }

    @Override
    public User createUser(User user) throws CharonException, DuplicateResourceException {
        return createUser(user, false);
    }

    @Override
    public User createUser(User user, boolean isBulkUserAdd) throws CharonException, DuplicateResourceException {

        String userStoreName = null;
        ServiceProvider serviceProvider = getServiceProvider(isBulkUserAdd);

        try {
            String userStoreDomainFromSP = getUserStoreDomainFromSP();
            if (userStoreDomainFromSP != null) {
                userStoreName = userStoreDomainFromSP;
            }
        } catch (IdentityApplicationManagementException e) {
            throw new CharonException("Error retrieving User Store name. ", e);
        }

        StringBuilder userName = new StringBuilder();

        if (StringUtils.isNotBlank(userStoreName)) {
            // if we have set a user store under provisioning configuration - we should only use
            // that.
            String currentUserName = user.getUserName();
            currentUserName = UserCoreUtil.removeDomainFromName(currentUserName);
            user.setUserName(userName.append(userStoreName)
                    .append(CarbonConstants.DOMAIN_SEPARATOR).append(currentUserName)
                    .toString());
        }

        String userStoreDomainName = IdentityUtil.extractDomainFromName(user.getUserName());
        if (StringUtils.isNotBlank(userStoreDomainName) && !isSCIMEnabled(userStoreDomainName)) {
            throw new CharonException("Cannot add user through scim to user store " + ". SCIM is not " +
                    "enabled for user store " + userStoreDomainName);
        }

        try {
            //TODO: Start tenant flow at the scim authentication point
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            carbonContext.setTenantDomain(MultitenantUtils.getTenantDomain(consumerName));
            carbonContext.getTenantId(true);
            carbonContext.setUsername(MultitenantUtils.getTenantAwareUsername(consumerName));

            //if operating in dumb mode, do not persist the operation, only provision to providers
            if (serviceProvider.getInboundProvisioningConfig().isDumbMode()) {
                if (log.isDebugEnabled()) {
                    log.debug("This instance is operating in dumb mode. " +
                              "Hence, operation is not persisted, it will only be provisioned."
                              + "provisioned user : " + user.getUserName());
                }
                this.provision(ProvisioningOperation.POST, user);
            } else {
                //Persist in carbon user store
                if (log.isDebugEnabled()) {
                    log.debug("Creating user: " + user.getUserName());
                }
                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
                SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
                Map<String, String> claimsMap = AttributeMapper.getClaimsMap(user);

                /*skip groups attribute since we map groups attribute to actual groups in ldap.
                and do not update it as an attribute in user schema*/
                if (claimsMap.containsKey(SCIMConstants.GROUPS_URI)) {
                    claimsMap.remove(SCIMConstants.GROUPS_URI);
                }

                /* Skip roles list since we map SCIM groups to local roles internally. It shouldn't be allowed to
                manipulate SCIM groups from user endpoint as this attribute has a mutability of "readOnly". Group
                changes must be applied via Group Resource */
                if (claimsMap.containsKey(SCIMConstants.ROLES_URI)) {
                    claimsMap.remove(SCIMConstants.ROLES_URI);
                }

                if (carbonUM.isExistingUser(user.getUserName())) {
                    String error = "User with the name: " + user.getUserName() + " already exists in the system.";
                    throw new DuplicateResourceException(error);
                }
                if (claimsMap.containsKey(SCIMConstants.USER_NAME_URI)) {
                    claimsMap.remove(SCIMConstants.USER_NAME_URI);
                }

                // location uri will not add to the userstore.
                if (claimsMap.containsKey(SCIMConstants.META_LOCATION_URI)) {
                    claimsMap.remove(SCIMConstants.META_LOCATION_URI);
                }
                Map<String, String> clonedClaimsMap = addUser(user, claimsMap);

                // The username will modify in the returned map.
                String modifiedUserName = null;
                if (clonedClaimsMap.containsKey(SCIMConstants.USER_NAME_URI)) {
                    modifiedUserName = clonedClaimsMap.get(SCIMConstants.USER_NAME_URI);
                    clonedClaimsMap.remove(SCIMConstants.USER_NAME_URI);
                }

                // Check if the user claims map passed has been modified.
                if (!claimsMap.equals(clonedClaimsMap)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Claims of user : " + user.getUserName() + " is updated. Populate updated claims.");
                    }

                    user.setUserName(modifiedUserName);

                    clonedClaimsMap.put(SCIMConstants.USER_NAME_URI, user.getUserName());
                    String userId = clonedClaimsMap.get(SCIMConstants.ID_URI);
                    clonedClaimsMap.put(SCIMConstants.META_LOCATION_URI, SCIMCommonUtils.getSCIMUserURL(userId));

                    //construct the SCIM Object from the attributes
                    try {
                        User newUser = (User) AttributeMapper
                                .constructSCIMObjectFromAttributes(clonedClaimsMap, SCIMConstants.USER_INT);
                        return newUser;
                    } catch (NotFoundException e) {
                        throw new CharonException("Failed to populate modified claims for user : " + user.getUserName() + " created.",
                                e);
                    }
                }
            }
        } catch (UserStoreException e) {
            String errMsg = "Error in adding the user: " + user.getUserName() + " to the user store. ";
            errMsg += e.getMessage();
            throw new CharonException(errMsg, e);
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }

        if (log.isDebugEnabled()) {
            log.debug("User: " + user.getUserName() + " is created through SCIM.");
        }

        return user;

    }

    private Map<String, String> addUser(User user, Map<String, String> claimsMap)
            throws org.wso2.carbon.user.core.UserStoreException, CharonException {

        // If the userId is generated from the kernel, gives priority to that userID.
        Map<String, String> claimsInLocalDialect = SCIMCommonUtils.convertSCIMtoLocalDialect(claimsMap);
        String generatedSCIMId = claimsInLocalDialect.get(USER_ID_CLAIM_URI);
        claimsInLocalDialect.remove(USER_ID_CLAIM_URI);
        carbonUM.addUser(user.getUserName(), user.getPassword(), null, claimsInLocalDialect, null);
        String userIDClaimValue = carbonUM.getUserClaimValue(user.getUserName(), USER_ID_CLAIM_URI, null);
        if (StringUtils.isEmpty(userIDClaimValue)) {
            carbonUM.setUserClaimValue(user.getUserName(), USER_ID_CLAIM_URI, generatedSCIMId, null);
            userIDClaimValue = generatedSCIMId;
        }
        claimsInLocalDialect.put(USER_ID_CLAIM_URI, userIDClaimValue);
        return SCIMCommonUtils.convertLocalToSCIMDialect(claimsInLocalDialect);
    }

    @Override
    public User getUser(String userId) throws CharonException {

        boolean isMe = SCIMCommonUtils.getThreadLocalToIdentifyMeEndpointCall();
        if (log.isDebugEnabled() && isMe) {
            log.debug("getUser() using scimID is called by /Me Endpoint for scimID: " + userId);
        }
        String authorization = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        if (log.isDebugEnabled()) {
            log.debug("Retrieving user: " + userId);
        }
        if (getServiceProvider(isBulkUserAdd).getInboundProvisioningConfig().isDumbMode()) {
            if (log.isDebugEnabled()) {
                log.debug("This instance is operating in dumb mode. Hence, do not retrieve users from user store");
            }
            User user = new User();
            user.setId(userId);
            return user;
        }
        User scimUser = null;
        try {
            Map<String, String> claimMappings;
            //get the user name of the user with this id
            String[] userNames = null;

            if (isMe) {
                // This is done to prevent user search with the scim id which is a performance hit for /Me calls.
                // We can use the username from threadLocal since this is the /Me call.
                if (log.isDebugEnabled()) {
                    log.debug("Username: " + authorization + " is taken from the ThreadLocal.");
                }
                userNames = new String[]{authorization};
            } else {
                userNames = carbonUM.getUserList(SCIMCommonUtils.getSCIMtoLocalMappings().get(SCIMConstants.ID_URI),
                        userId, UserCoreConstants.DEFAULT_PROFILE);
                if (log.isDebugEnabled()) {
                    log.debug("Username: " + Arrays.toString(userNames) + " is retrieved for scimID: " + userId);
                }
            }

            if (ArrayUtils.isEmpty(userNames)) {
                if (log.isDebugEnabled()) {
                    log.debug("User with SCIM id: " + userId + " does not exist in the system.");
                }
                return null;
            } else {
                //get claims related to SCIM claim dialect
                List<String> claimURIList;
                claimMappings = SCIMCommonUtils.getSCIMtoLocalMappings();
                if (MapUtils.isNotEmpty(claimMappings)) {
                    claimURIList = new ArrayList<>(claimMappings.values());
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("SCIM to Local Claim mappings list is empty.");
                    }
                    claimURIList = new ArrayList<>();
                }
                //we assume (since id is unique per user) only one user exists for a given id
                scimUser = this.getSCIMUser(getAuthorizedDomainUser(userNames, authorization), claimURIList, userId);
                if (log.isDebugEnabled()) {
                    log.debug("User: " + scimUser.getUserName() + " is retrieved through SCIM.");
                }
            }

        } catch (UserStoreException e) {
            throw new CharonException("Error in getting user information from Carbon User Store for" +
                    "user: " + userId, e);
        }
        return scimUser;
    }

    @Override
    public List<User> listUsers() throws CharonException {

        List<User> users = new ArrayList<>();
        try {
            String[] userNames = carbonUM.getUserList(SCIMCommonUtils.getSCIMtoLocalMappings().get(SCIMConstants
                    .ID_URI), "*", null);
            if (userNames != null && userNames.length != 0) {
                for (String userName : userNames) {
                    if (userName.contains(UserCoreConstants.NAME_COMBINER)) {
                        userName = userName.split("\\" + UserCoreConstants.NAME_COMBINER)[0];
                    }
                    User scimUser = this.getSCIMUserWithoutRoles(userName, new ArrayList<String>());
                    if (scimUser != null) {
                        Map<String, Attribute> attrMap = scimUser.getAttributeList();
                        if (attrMap != null && !attrMap.isEmpty()) {
                            users.add(scimUser);
                        }
                    }
                }
            }
        } catch (UserStoreException e) {
            throw new CharonException("Error while retrieving users from user store..", e);
        }
        return users;
    }

    @Override
    public List<User> listUsersByAttribute(Attribute attribute) {
        return Collections.emptyList();
    }

    @Override
    public List<User> listFilteredUsersWithAttributes(List<String> attributeURIs, String filterAttributeName, String
            filterOperation, String filterValue) throws CharonException {

        List<User> users = new ArrayList<>();
        String[] userNames = null;
        try {
            if (filterAttributeName != null) {
                userNames = getFilteredUserList(filterAttributeName, filterOperation, filterValue);
            } else {
                userNames = carbonUM.getUserList(SCIMCommonUtils.getSCIMtoLocalMappings().get(SCIMConstants.ID_URI),
                        "*", null);
            }
            if (userNames != null && userNames.length != 0) {
                for (String userName : userNames) {
                    if (userName.contains(UserCoreConstants.NAME_COMBINER)) {
                        userName = userName.split("\\" + UserCoreConstants.NAME_COMBINER)[0];
                    }
                    User scimUser = this.getSCIMUserWithoutRoles(userName, attributeURIs);
                    if (scimUser != null) {
                        Map<String, Attribute> attrMap = scimUser.getAttributeList();
                        if (attrMap != null && !attrMap.isEmpty()) {
                            users.add(scimUser);
                        }
                    }
                }
            }
        } catch (UserStoreException e) {
            throw new CharonException("Error while retrieving users from user store.", e);
        }
        return users;
    }

    @Override
    public List<User> listUsersByFilter(String attributeName, String filterOperation,
                                        String attributeValue) throws CharonException {
        //since we only support eq filter operation at the moment, no need to check for that.
        if (log.isDebugEnabled()) {
            log.debug("Listing users by filter: " + attributeName + filterOperation +
                    attributeValue);
        }
        List<User> filteredUsers = new ArrayList<>();
        User scimUser = null;
        try {
            String[] userNames = getFilteredUserList(attributeName, filterOperation, attributeValue);

            if (userNames == null || userNames.length == 0) {
                if (log.isDebugEnabled()) {
                    log.debug("Users with filter: " + attributeName + filterOperation +
                            attributeValue + " does not exist in the system.");
                }
                return Collections.emptyList();
            } else {
                for (String userName : userNames) {

                    if (CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME.equals(userName)) {
                        continue;
                    }

                    List<String> claimURIList = new ArrayList<>();
                    if (Boolean.parseBoolean(IdentityUtil.getProperty(SCIMProviderConstants
                            .ELEMENT_NAME_SHOW_ALL_USER_DETAILS))) {
                        // Get claims related to SCIM claim dialect.
                        ClaimMapping[] claims = carbonClaimManager.
                                getAllClaimMappings(SCIMCommonConstants.SCIM_CLAIM_DIALECT);
                        for (ClaimMapping claim : claims) {
                            claimURIList.add(claim.getClaim().getClaimUri());
                        }
                    }
                    scimUser = this.getSCIMUserWithoutRoles(userName, claimURIList);
                    //if SCIM-ID is not present in the attributes, skip
                    if (scimUser != null && StringUtils.isBlank(scimUser.getId())) {
                        continue;
                    }
                    filteredUsers.add(scimUser);
                }
                if (log.isDebugEnabled()) {
                    log.debug("Users filtered through SCIM for the filter: " + attributeName + filterOperation +
                            attributeValue);
                }
            }

        } catch (UserStoreException e) {
            throw new CharonException("Error in filtering users by attribute name : " + attributeName + ", " +
                    "attribute value : " + attributeValue + " and filter operation " + filterOperation, e);
        }
        return filteredUsers;
    }

    private String[] getFilteredUserList(String attributeName, String filterOperation,
                                         String attributeValue)
            throws org.wso2.carbon.user.core.UserStoreException {

        String[] userNames = null;
        if (!SCIMConstants.GROUPS_URI.equals(attributeName)) {

            // Get claim mappings from SCIM to Local dialect.
            Map<String, String> claimMappings = SCIMCommonUtils.getSCIMtoLocalMappings();
            if (MapUtils.isNotEmpty(claimMappings) && StringUtils.isNotEmpty(claimMappings.get(attributeName))) {
                attributeName = claimMappings.get(attributeName);
            }
            //get the user name of the user with this id
            userNames = carbonUM.getUserList(attributeName, attributeValue, UserCoreConstants.DEFAULT_PROFILE);
        } else {
            userNames = carbonUM.getUserListOfRole(attributeValue);
        }
        return userNames;
    }

    @Override
    public List<User> listUsersBySort(String s, String s1) {
        return Collections.emptyList();
    }

    @Override
    public List<User> listUsersWithPagination(int i, int i1) {
        return Collections.emptyList();
    }

    @Override
    public User updateUser(User user) throws CharonException {
        if (getServiceProvider(isBulkUserAdd).getInboundProvisioningConfig().isDumbMode()) {
            if (log.isDebugEnabled()) {
                log.debug("This instance is operating in dumb mode. " +
                          "Hence, operation is not persisted, it will only be provisioned.");
            }
            this.provision(ProvisioningOperation.PUT, user);
            return user;

        }
            if (log.isDebugEnabled()) {
                log.debug("Updating user: " + user.getUserName());
            }
            try {
                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
                SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
                //get user claim values
                Map<String, String> claims = AttributeMapper.getClaimsMap(user);

                //check if username of the updating user existing in the userstore.
                //TODO:immutable userId can be something else other than username. eg: mail.
                //Therefore, correct way is to check the corresponding SCIM attribute for the
                //UserNameAttribute of user-mgt.xml.
                // Refer: SCIMUserOperationListener#isProvisioningActionAuthorized method.
                try {
                    String userStoreDomainFromSP = getUserStoreDomainFromSP();
                    User oldUser = this.getUser(user.getId());
                    if (userStoreDomainFromSP != null && !userStoreDomainFromSP
                            .equalsIgnoreCase(IdentityUtil.extractDomainFromName(oldUser.getUserName()))) {
                        throw new CharonException("User :" + oldUser.getUserName() + "is not belong to user store " +
                                                  userStoreDomainFromSP + "Hence user updating fail");
                    }
                    if (getUserStoreDomainFromSP() != null &&
                        !UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME.equalsIgnoreCase(getUserStoreDomainFromSP())) {
                        user.setUserName(IdentityUtil
                                                 .addDomainToName(UserCoreUtil.removeDomainFromName(user.getUserName()),
                                                                  getUserStoreDomainFromSP()));
                    }
                } catch (IdentityApplicationManagementException e) {
                    throw new CharonException("Error retrieving User Store name. ", e);
                }
                if (!carbonUM.isExistingUser(user.getUserName())) {
                    throw new CharonException("User name is immutable in carbon user store.");
                }

                /*skip groups attribute since we map groups attribute to actual groups in ldap.
                and do not update it as an attribute in user schema*/
                if (claims.containsKey(SCIMConstants.GROUPS_URI)) {
                    claims.remove(SCIMConstants.GROUPS_URI);
                }

                if (claims.containsKey(SCIMConstants.USER_NAME_URI)) {
                    claims.remove(SCIMConstants.USER_NAME_URI);
                }

                /* Skip roles list since we map SCIM groups to local roles internally. It shouldn't be allowed to
                manipulate SCIM groups from user endpoint as this attribute has a mutability of "readOnly". Group
                changes must be applied via Group Resource. */
                if (claims.containsKey(SCIMConstants.ROLES_URI)) {
                    claims.remove(SCIMConstants.ROLES_URI);
                }

                List<String> claimURIList;
                Map<String, String> claimMappings = SCIMCommonUtils.getSCIMtoLocalMappings();
                if (MapUtils.isNotEmpty(claimMappings)) {
                    claimURIList = new ArrayList<>(claimMappings.values());
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("SCIM to Local Claim mappings list is empty.");
                    }
                    claimURIList = new ArrayList<>();
                }

                Map<String, String> oldClaimList = carbonUM.getUserClaimValues(user.getUserName(), claimURIList
                        .toArray(new String[claimURIList.size()]), null);

                for (Map.Entry<String, String> entry : oldClaimList.entrySet()) {
                    if (!isImmutableClaim(entry.getKey())) {
                        carbonUM.deleteUserClaimValue(user.getUserName(), entry.getKey(), null);
                    }
                }

                // Get user claims mapped from SCIM dialect to WSO2 dialect.
                Map<String, String> claimValuesInLocalDialect = SCIMCommonUtils.convertSCIMtoLocalDialect(claims);
                //set user claim values
                carbonUM.setUserClaimValues(user.getUserName(), claimValuesInLocalDialect, null);
                //if password is updated, set it separately
                if (user.getPassword() != null) {
                    carbonUM.updateCredentialByAdmin(user.getUserName(), user.getPassword());
                }
                if (log.isDebugEnabled()) {
                    log.debug("User: " + user.getUserName() + " updated through SCIM.");
                }
            } catch (UserStoreException e) {
                throw new CharonException("Error while updating attributes of user: " + user.getUserName(), e);
            }

            return user;
    }

    @Override
    public User patchUser(User newUser, User oldUser, String[] attributesToDelete) throws CharonException {

        if (getServiceProvider(isBulkUserAdd).getInboundProvisioningConfig().isDumbMode()) {
            if (log.isDebugEnabled()) {
                log.debug("This instance is operating in dumb mode. " +
                          "Hence, operation is not persisted, it will only be provisioned.");
            }
            this.provision(ProvisioningOperation.PATCH, newUser);
            return newUser;
        }
            if (log.isDebugEnabled()) {
                log.debug("Updating user: " + newUser.getUserName());
            }
        try {
            String userStoreDomainFromSP = getUserStoreDomainFromSP();
            if (userStoreDomainFromSP != null &&
                !userStoreDomainFromSP.equalsIgnoreCase(IdentityUtil.extractDomainFromName(oldUser.getUserName()))) {
                throw new CharonException("User :" + oldUser.getUserName() + "is not belong to user store " +
                                          userStoreDomainFromSP + "Hence user updating fail");
            }
        } catch (IdentityApplicationManagementException e) {
            throw new CharonException("Error retrieving User Store name. ", e);
        }

        try {
                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
                SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
                //get user claim values
                Map<String, String> claims = AttributeMapper.getClaimsMap(newUser);
                if (IdentityUtil.extractDomainFromName(newUser.getUserName())
                                .equals(UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME) &&
                    !(IdentityUtil.extractDomainFromName(oldUser.getUserName())
                                  .equals(UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME))) {
                    newUser.setUserName(
                            IdentityUtil.addDomainToName(newUser.getUserName(),
                                    IdentityUtil.extractDomainFromName(oldUser.getUserName())));
                }

                //check if username of the updating user existing in the userStore.
                if (!carbonUM.isExistingUser(newUser.getUserName())) {
                    throw new CharonException("User name is immutable in carbon user store.");
                }

                /*skip groups attribute since we map groups attribute to actual groups in ldap.
                and do not update it as an attribute in user schema*/
                if (claims.containsKey(SCIMConstants.GROUPS_URI)) {
                    claims.remove(SCIMConstants.GROUPS_URI);
                }

                if (claims.containsKey(SCIMConstants.USER_NAME_URI)) {
                    claims.remove(SCIMConstants.USER_NAME_URI);
                }

                /* Skip roles list since we map SCIM groups to local roles internally. It shouldn't be allowed to
                manipulate SCIM groups from user endpoint as this attribute has a mutability of "readOnly". Group changes
                must be applied via Group Resource. */
                if (claims.containsKey(SCIMConstants.ROLES_URI)) {
                    claims.remove(SCIMConstants.ROLES_URI);
                }

                List<String> claimsToDelete = new ArrayList<>();
                Map<String, String> claimMappings = SCIMCommonUtils.getSCIMtoLocalMappings();

                if (MapUtils.isNotEmpty(claimMappings)) {
                    for (String anAttributesToDelete : attributesToDelete) {
                        String localClaimUri = claimMappings.get(SCIMConstants.CORE_SCHEMA_URI + ":" +
                                anAttributesToDelete);
                        if (StringUtils.isNotEmpty(localClaimUri)) {
                            claimsToDelete.add(localClaimUri);
                        }
                    }
                }
                if (CollectionUtils.isNotEmpty(claimsToDelete)) {
                    carbonUM.deleteUserClaimValues(oldUser.getUserName(),
                            claimsToDelete.toArray(new String[claimsToDelete.size()]), null);
                }
                // Get user claims mapped from SCIM dialect to WSO2 dialect.
                Map<String, String> claimValuesInLocalDialect = SCIMCommonUtils.convertSCIMtoLocalDialect(claims);

                //set user claim values
                carbonUM.setUserClaimValues(oldUser.getUserName(), claimValuesInLocalDialect, null);
                //if password is updated, set it separately
                if (StringUtils.isNotEmpty(newUser.getPassword())) {
                    carbonUM.updateCredentialByAdmin(newUser.getUserName(), newUser.getPassword());
                }
                if (log.isDebugEnabled()) {
                    log.debug("User: " + newUser.getUserName() + " updated through SCIM.");
                }
        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            String message = "Error while updating attributes of user: " + newUser.getUserName() + ". ";
            if (StringUtils.isNotBlank(e.getMessage())) {
                throw new CharonException(message + e.getMessage(), e);
            } else {
                throw new CharonException(message, e);
            }
        }

            return newUser;
    }

    public User updateUser(List<Attribute> attributes) {
        return null;
    }

    @Override
    public void deleteUser(String userId) throws NotFoundException, CharonException {
        if (getServiceProvider(isBulkUserAdd).getInboundProvisioningConfig().isDumbMode()) {
            if (log.isDebugEnabled()) {
                log.debug("This instance is operating in dumb mode. " +
                          "Hence, operation is not persisted, it will only be provisioned.");
            }
            User user = new User();
            user.setId(userId);
            this.provision(ProvisioningOperation.DELETE, user);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Deleting user: " + userId);
            }
            //get the user name of the user with this id
            String[] userNames = null;
            String userName = null;
            try {
                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
                SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
                userNames = carbonUM.getUserList(SCIMCommonUtils.getSCIMtoLocalMappings().get(SCIMConstants.ID_URI),
                        userId, UserCoreConstants.DEFAULT_PROFILE);
                String userStoreDomainFromSP = null;
                try {
                    userStoreDomainFromSP = getUserStoreDomainFromSP();
                } catch (IdentityApplicationManagementException e) {
                    throw new CharonException("Error retrieving User Store name. ", e);
                }
                if (userNames == null || userNames.length == 0) {
                    //resource with given id not found
                    if (log.isDebugEnabled()) {
                        log.debug("User with id: " + userId + " not found.");
                    }
                    throw new NotFoundException();
                } else if (userStoreDomainFromSP != null &&
                           !(userStoreDomainFromSP
                                   .equalsIgnoreCase(IdentityUtil.extractDomainFromName(userNames[0])))) {
                    throw new CharonException("User :" + userNames[0] + "is not belong to user store " +
                                              userStoreDomainFromSP + "Hence user updating fail");
                } else {
                    //we assume (since id is unique per user) only one user exists for a given id
                    userName = userNames[0];
                    carbonUM.deleteUser(userName);
                    if (log.isDebugEnabled()) {
                        log.debug("User: " + userName + " is deleted through SCIM.");
                    }
                }

            } catch (org.wso2.carbon.user.core.UserStoreException e) {
                throw new CharonException("Error in deleting user: " + userName, e);
            }
        }
    }

    @Override
    public Group createGroup(Group group) throws CharonException, DuplicateResourceException {
        if (getServiceProvider(isBulkUserAdd).getInboundProvisioningConfig().isDumbMode()) {
            if (log.isDebugEnabled()) {
                log.debug("This instance is operating in dumb mode. " +
                          "Hence, operation is not persisted, it will only be provisioned.");
            }
            this.provision(ProvisioningOperation.POST, group);
            return group;
        }
            if (log.isDebugEnabled()) {
                log.debug("Creating group: " + group.getDisplayName());
            }
            String domainName = "";
            try {
                //modify display name if no domain is specified, in order to support multiple user store feature
                String originalName = group.getDisplayName();
                String roleNameWithDomain = null;
                try {
                    if (getUserStoreDomainFromSP() != null) {
                        domainName = getUserStoreDomainFromSP();
                        roleNameWithDomain = IdentityUtil
                                .addDomainToName(UserCoreUtil.removeDomainFromName(originalName), domainName);
                    } else if (originalName.indexOf(CarbonConstants.DOMAIN_SEPARATOR) > 0) {
                        domainName = IdentityUtil.extractDomainFromName(originalName);
                        roleNameWithDomain = IdentityUtil
                                .addDomainToName(UserCoreUtil.removeDomainFromName(originalName), domainName);
                    } else {
                        domainName = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;
                        roleNameWithDomain = SCIMCommonUtils.getGroupNameWithDomain(originalName);
                    }
                } catch (IdentityApplicationManagementException e) {
                    throw new CharonException("Error retrieving User Store name. ", e);
                }

                if (!isInternalOrApplicationGroup(domainName) && StringUtils.isNotBlank(domainName) && !isSCIMEnabled
                        (domainName)) {
                    throw new CharonException("Cannot add user through scim to user store " + ". SCIM is not " +
                            "enabled for user store " + domainName);
                }
                group.setDisplayName(roleNameWithDomain);
                //check if the group already exists
                if (carbonUM.isExistingRole(group.getDisplayName(), false)) {
                    String error = "Group with name: " + group.getDisplayName() +
                            " already exists in the system.";
                    throw new DuplicateResourceException(error);
                }

                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
                SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
                /*if members are sent when creating the group, check whether users already exist in the
                user store*/
                List<String> userIds = group.getMembers();
                List<String> userDisplayNames = group.getMembersWithDisplayName();
                if (CollectionUtils.isNotEmpty(userIds)) {
                    List<String> members = new ArrayList<>();
                    for (String userId : userIds) {
                        String[] userNames = carbonUM.getUserList(SCIMCommonUtils.getSCIMtoLocalMappings()
                                .get(SCIMConstants.ID_URI), userId, UserCoreConstants.DEFAULT_PROFILE);
                        if (userNames == null || userNames.length == 0) {
                            String error = "User: " + userId + " doesn't exist in the user store. " +
                                    "Hence, can not create the group: " + group.getDisplayName();
                            throw new IdentitySCIMException(error);
                        } else if (userNames[0].indexOf(UserCoreConstants.DOMAIN_SEPARATOR) > 0 &&
                                   !StringUtils.containsIgnoreCase(userNames[0], domainName)) {
                            String error = "User: " + userId + " doesn't exist in the same user store. " +
                                    "Hence, can not create the group: " + group.getDisplayName();
                            throw new IdentitySCIMException(error);
                        } else {
                            members.add(userNames[0]);
                            if (CollectionUtils.isNotEmpty(userDisplayNames)) {
                                boolean userContains = false;
                                for (String user : userDisplayNames) {
                                    user =
                                            user.indexOf(UserCoreConstants.DOMAIN_SEPARATOR) > 0
                                                    ? user.split(UserCoreConstants.DOMAIN_SEPARATOR)[1]
                                                    : user;
                                    if (user.equalsIgnoreCase(userNames[0].indexOf(UserCoreConstants.DOMAIN_SEPARATOR) > 0
                                            ? userNames[0].split(UserCoreConstants.DOMAIN_SEPARATOR)[1]
                                            : userNames[0])) {
                                        userContains = true;
                                        break;
                                    }
                                }
                                if (!userContains) {
                                    throw new IdentitySCIMException(
                                            "Given SCIM user Id and name not matching..");
                                }
                            }
                        }
                    }

                    if (!isUniqueGroupIdEnabled(domainName)) {
                        // Add other scim attributes in the identity DB since user store doesn't support some attributes.
                        SCIMGroupHandler scimGroupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
                        scimGroupHandler.createSCIMAttributes(group);
                    }
                    carbonUM.addRole(group.getDisplayName(),
                            members.toArray(new String[members.size()]), null, false);
                    if (log.isDebugEnabled()) {
                        log.debug("Group: " + group.getDisplayName() + " is created through SCIM.");
                    }
                } else {
                    if (!isUniqueGroupIdEnabled(domainName)) {
                        // Add other scim attributes in the identity DB since user store doesn't support some attributes.
                        SCIMGroupHandler scimGroupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
                        scimGroupHandler.createSCIMAttributes(group);
                    }
                    carbonUM.addRole(group.getDisplayName(), null, null, false);
                    if (log.isDebugEnabled()) {
                        log.debug("Group: " + group.getDisplayName() + " is created through SCIM.");
                    }
                }
                if (isUniqueGroupIdEnabled(domainName)) {
                    org.wso2.carbon.user.core.common.Group retrievedGroup =
                            carbonUM.getGroupByGroupName(group.getDisplayName(), null);
                    return buildGroup(retrievedGroup);
                }
            } catch (UserStoreException e) {
                try {
                    if (!isUniqueGroupIdEnabled(domainName)) {
                        SCIMGroupHandler scimGroupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
                        scimGroupHandler.deleteGroupAttributes(group.getDisplayName());
                    }
                } catch (UserStoreException | IdentitySCIMException ex) {
                    log.error("Error occurred while doing rollback operation of the SCIM table entry for role: " + group.getDisplayName(), ex);
                    throw new CharonException("Error occurred while doing rollback operation of the SCIM table entry for role: " + group.getDisplayName(), e);
                }
                throw new CharonException("Error occurred while adding role : " + group.getDisplayName(), e);
            } catch (IdentitySCIMException e) {
                //This exception can occurr because of scimGroupHandler.createSCIMAttributes(group) or
                //userContains=false. Therefore contextual message could not be provided.
                throw new CharonException("Error in creating group", e);
            }
            //TODO:after the group is added, read it from user store and return
            return group;
    }

    @Override
    public Group getGroup(String id) throws CharonException {
        if (log.isDebugEnabled()) {
            log.debug("Retrieving group with id: " + id);
        }
        if (getServiceProvider(isBulkUserAdd).getInboundProvisioningConfig().isDumbMode()) {
            if (log.isDebugEnabled()) {
                log.debug("This instance is operating in dumb mode. Hence, do not retrieve users from user store");
            }
            Group group = new Group();
            group.setId(id);
            return group;
        }
        Group group = null;
        try {
            String groupName;
             /* Since no userstore domain available at the moment we are using the primary userstore domain for getting
             default configs since we are not encouraging to disable group id enable feature. */
            if (isUniqueGroupIdEnabled(UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME)) {
                org.wso2.carbon.user.core.common.Group retrievedGroup = carbonUM.getGroup(id, null);
                if (retrievedGroup != null && StringUtils.isNotBlank(retrievedGroup.getGroupName())) {
                    groupName = retrievedGroup.getGroupName();
                } else {
                    groupName = null;
                }
            } else {
                SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
                // Get group name by Id.
                groupName = groupHandler.getGroupName(id);
            }

            if (groupName != null) {
                group = getGroupWithName(groupName);
            } else {
                //returning null will send a resource not found error to client by Charon.
                return null;
            }
        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            throw new CharonException("Error in retrieving group : " + id, e);
        } catch (IdentitySCIMException e) {
            throw new CharonException("Error in retrieving SCIM Group information from database.", e);
        }
        return group;
    }

    @Override
    public List<Group> listGroups() throws CharonException {
        List<Group> groupList = new ArrayList<>();
        Set<String> roleNames;
        try {
             /* Since no userstore domain available at the moment we are using the primary userstore domain for getting
             default configs since we are not encouraging to disable group id enable feature. */
            if (isUniqueGroupIdEnabled(UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME)) {
                roleNames = new HashSet<>(Arrays.asList(carbonUM.getRoleNames()));
            } else {
                SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
                roleNames = groupHandler.listSCIMRoles();
            }

            for (String roleName : roleNames) {
                Group group = this.getGroupOnlyWithMetaAttributes(roleName);
                if (group != null && group.getId() != null) {
                    groupList.add(group);
                }
            }
        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            String errMsg = "Error in obtaining role names from user store.";
            errMsg += e.getMessage();
            throw new CharonException(errMsg, e);
        } catch (IdentitySCIMException e) {
            throw new CharonException("Error in retrieving SCIM Group information from database.", e);
        }
        return groupList;
    }

    @Override
    public List<Group> listGroupsByAttribute(Attribute attribute) throws CharonException {
        return Collections.emptyList();
    }

    @Override
    public List<Group> listGroupsByFilter(String filterAttribute, String filterOperation,
                                          String attributeValue) throws CharonException {
        //since we only support "eq" filter operation for group name currently, no need to check for that.
        if (log.isDebugEnabled()) {
            log.debug("Listing groups with filter: " + filterAttribute + filterOperation +
                    attributeValue);
        }
        List<Group> filteredGroups = new ArrayList<>();
        Group group = null;
        try {
            if (attributeValue == null) {
                //returning null will send a resource not found error to client by Charon.
                return Collections.emptyList();

            } else {
                if (carbonUM instanceof AbstractUserStoreManager &&
                        SCIMProviderConstants.DISPLAY_NAME.equalsIgnoreCase(filterAttribute) &&
                        attributeValue.contains(SCIMProviderConstants.WILDCARD_ASTERISK)) {

                    AbstractUserStoreManager abstractUserStoreManager = (AbstractUserStoreManager) carbonUM;
                    String[] roles = abstractUserStoreManager.getRoleNames(attributeValue, -1, false, true, true);

                    if (roles != null) {
                        for (String role: roles) {
                            group = buildGroup(role);
                            filteredGroups.add(group);
                        }
                    }
                } else if (carbonUM.isExistingRole(attributeValue, false)) {
                    //skip internal roles
                    if ((CarbonConstants.REGISTRY_ANONNYMOUS_ROLE_NAME.equals(attributeValue)) ||
                            UserCoreUtil.isEveryoneRole(attributeValue, carbonUM.getRealmConfiguration()) ||
                            UserCoreUtil.isPrimaryAdminRole(attributeValue, carbonUM.getRealmConfiguration())) {
                        throw new IdentitySCIMException("Internal roles do not support SCIM.");
                    }
                    /********we expect only one result**********/
                    //construct the group name with domain -if not already provided, in order to support
                    //multiple user store feature with SCIM.
                    group = buildGroup(attributeValue);
                    filteredGroups.add(group);
                } else {
                    //returning null will send a resource not found error to client by Charon.
                    return Collections.emptyList();
                }
            }
        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            throw new CharonException("Error in filtering groups by attribute name : " + filterAttribute + ", " +
                    "attribute value : " + attributeValue + " and filter operation " + filterOperation, e);
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new CharonException("Error in filtering group with filter: "
                    + filterAttribute + filterOperation + attributeValue, e);
        } catch (IdentitySCIMException e) {
            throw new CharonException("Error in retrieving SCIM Group information from database.", e);
        }
        return filteredGroups;
    }

    private Group buildGroup(String role) throws CharonException, IdentitySCIMException, UserStoreException {
        String groupNameWithDomain;
        if (role.indexOf(CarbonConstants.DOMAIN_SEPARATOR) > 0) {
            groupNameWithDomain = role;
        } else {
            groupNameWithDomain = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME +
                    CarbonConstants.DOMAIN_SEPARATOR + role;
        }
        return getGroupOnlyWithMetaAttributes(groupNameWithDomain);
    }

    @Override
    public List<Group> listGroupsBySort(String s, String s1) throws CharonException {
        return Collections.emptyList();
    }

    @Override
    public List<Group> listGroupsWithPagination(int i, int i1) {
        return Collections.emptyList();
    }

    @Override
    public Group updateGroup(Group oldGroup, Group newGroup) throws CharonException {

        //if operating in dumb mode, do not persist the operation, only provision to providers
        if (getServiceProvider(isBulkUserAdd).getInboundProvisioningConfig().isDumbMode()) {
            if (log.isDebugEnabled()) {
                log.debug("This instance is operating in dumb mode. " +
                          "Hence, operation is not persisted, it will only be provisioned.");
            }
            this.provision(ProvisioningOperation.PUT, newGroup);
            return newGroup;
        }
        try {
            String userStoreDomainFromSP = getUserStoreDomainFromSP();
            if (userStoreDomainFromSP != null && !userStoreDomainFromSP.equalsIgnoreCase(
                    IdentityUtil.extractDomainFromName(oldGroup.getDisplayName()))) {
                throw new CharonException("Group :" + oldGroup.getDisplayName() + "is not belong to user store " +
                                          userStoreDomainFromSP + "Hence group updating fail");
            }
        } catch (IdentityApplicationManagementException e) {
            throw new CharonException("Error retrieving User Store name. ", e);
        }

        oldGroup.setDisplayName(IdentityUtil.addDomainToName(UserCoreUtil.removeDomainFromName(oldGroup.getDisplayName()),
                IdentityUtil.extractDomainFromName(oldGroup.getDisplayName())));
        newGroup.setDisplayName(IdentityUtil.addDomainToName(UserCoreUtil.removeDomainFromName(newGroup.getDisplayName()),
                IdentityUtil.extractDomainFromName(newGroup.getDisplayName())));

            try {
                String primaryDomain = IdentityUtil.getPrimaryDomainName();
                if (IdentityUtil.extractDomainFromName(newGroup.getDisplayName()).equals(primaryDomain) && !(IdentityUtil
                        .extractDomainFromName(oldGroup.getDisplayName())
                        .equals(primaryDomain))) {
                    String userStoreDomain = IdentityUtil.extractDomainFromName(oldGroup.getDisplayName());
                    newGroup.setDisplayName(IdentityUtil.addDomainToName(newGroup.getDisplayName(), userStoreDomain));

                } else if (!IdentityUtil.extractDomainFromName(oldGroup.getDisplayName())
                        .equals(IdentityUtil.extractDomainFromName(newGroup.getDisplayName()))) {
                    throw new IdentitySCIMException(
                            "User store domain of the group is not matching with the given SCIM group Id.");
                }

                newGroup.setDisplayName(SCIMCommonUtils.getGroupNameWithDomain(newGroup.getDisplayName()));
                oldGroup.setDisplayName(SCIMCommonUtils.getGroupNameWithDomain(oldGroup.getDisplayName()));

                if (log.isDebugEnabled()) {
                    log.debug("Updating group: " + oldGroup.getDisplayName());
                }

                String groupName = newGroup.getDisplayName();
                String userStoreDomainForGroup = IdentityUtil.extractDomainFromName(groupName);

                if (newGroup.getMembers() != null && !(newGroup.getMembers().isEmpty()) &&
                        !isInternalOrApplicationGroup(userStoreDomainForGroup)) {
                    newGroup = addDomainToUserMembers(newGroup, userStoreDomainForGroup);
                }

                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
                SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);

                boolean updated = false;
                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
                SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
                //check if the user ids sent in updated group exist in the user store and the associated user name
                //also a matching one.
                List<String> userIds = newGroup.getMembers();
                List<String> userDisplayNames = newGroup.getMembersWithDisplayName();

                /* compare user store domain of group and user store domain of user name , if there is a mismatch do not
                 update the group */
                if (userDisplayNames != null && userDisplayNames.size() > 0) {
                    for (String userDisplayName : userDisplayNames) {
                        String userStoreDomainForUser =
                                IdentityUtil.extractDomainFromName(userDisplayName);
                        if (!isInternalOrApplicationGroup(userStoreDomainForGroup) && !userStoreDomainForGroup.equalsIgnoreCase
                                (userStoreDomainForUser)) {
                            throw new IdentitySCIMException(
                                    userDisplayName + " does not " + "belongs to user store " + userStoreDomainForGroup);
                        }

                    }
                }

                if (CollectionUtils.isNotEmpty(userIds)) {
                    String[] userNames = null;
                    for (String userId : userIds) {
                        userNames = carbonUM.getUserList(SCIMConstants.ID_URI,
                                IdentityUtil.addDomainToName(userId, userStoreDomainForGroup),
                                UserCoreConstants.DEFAULT_PROFILE);
                        if (userNames == null || userNames.length == 0) {
                            String error = "User: " + userId + " doesn't exist in the user store. " +
                                    "Hence, can not update the group: " + oldGroup.getDisplayName();
                            throw new IdentitySCIMException(error);
                        } else {
                            if (!UserCoreUtil.isContain(UserCoreUtil.removeDomainFromName(userNames[0]),
                                    UserCoreUtil.removeDomainFromNames(userDisplayNames.toArray(
                                            new String[userDisplayNames.size()])))) {
                                throw new IdentitySCIMException("Given SCIM user Id and name not matching..");
                            }
                        }
                    }
                }
                //we do not update Identity_SCIM DB here since it is updated in SCIMUserOperationListener's methods.

                //update name if it is changed
                if (!(oldGroup.getDisplayName().equalsIgnoreCase(newGroup.getDisplayName()))) {
                    //update group name in carbon UM
                    carbonUM.updateRoleName(oldGroup.getDisplayName(),
                                            newGroup.getDisplayName());

                    updated = true;
                }

                //find out added members and deleted members..
                List<String> oldMembers = oldGroup.getMembersWithDisplayName();
                List<String> newMembers = newGroup.getMembersWithDisplayName();

                //get users with operation":"delete"
                List<String> deleteRequestedMembers =
                        newGroup.getMembersWithDisplayName(SCIMConstants.CommonSchemaConstants.OPERATION_DELETE);

                if (newMembers != null) {
                    //Remove users with operation":"delete" from newMembers list
                    newMembers.removeAll(deleteRequestedMembers);

                    List<String> addedMembers = new ArrayList<>();
                    List<String> deletedMembers = new ArrayList<>();

                    //check for deleted members
                    if (CollectionUtils.isNotEmpty(oldMembers)) {
                        for (String oldMember : oldMembers) {
                            if (newMembers.contains(oldMember)) {
                                continue;
                            }
                            deletedMembers.add(oldMember);
                        }
                    }

                    //check for added members
                    if (CollectionUtils.isNotEmpty(newMembers)) {
                        for (String newMember : newMembers) {
                            if (oldMembers != null && oldMembers.contains(newMember)) {
                                continue;
                            }
                            addedMembers.add(newMember);
                        }
                    }

                    if (CollectionUtils.isNotEmpty(addedMembers) || CollectionUtils.isNotEmpty(deletedMembers)) {
                        carbonUM.updateUserListOfRole(newGroup.getDisplayName(),
                                deletedMembers.toArray(new String[deletedMembers.size()]),
                                addedMembers.toArray(new String[addedMembers.size()]));
                        updated = true;
                    }
                }
                if (updated) {
                    if (log.isDebugEnabled()) {
                        log.debug("Group: " + newGroup.getDisplayName() + " is updated through SCIM.");
                    }
                } else {
                    log.warn("There is no updated field in the group: " + oldGroup.getDisplayName() +
                            ". Therefore ignoring the provisioning.");
                }

            } catch (UserStoreException | IdentitySCIMException e) {
                throw new CharonException("Error occurred while updating old group : " + oldGroup.getDisplayName(), e);
            }
            return newGroup;
    }

    @Override
    public Group updateGroup(List<Attribute> attributes) throws CharonException {
        return null;
    }

    /**
     * this method similar to updateGroup but new changes to group will be merged without changing existing data
     *
     * @param oldGroup existing group meta information
     * @param newGroup new changes required for existing group
     * @return updated group information
     * @throws CharonException
     */
    @Override
    public Group patchGroup(Group oldGroup, Group newGroup) throws CharonException {
        //if operating in dumb mode, do not persist the operation, only provision to providers

        if (getServiceProvider(isBulkUserAdd).getInboundProvisioningConfig().isDumbMode()) {
            if (log.isDebugEnabled()) {
                log.debug("This instance is operating in dumb mode. Hence, operation is not persisted, it will " +
                        "only be provisioned.");
            }
            this.provision(ProvisioningOperation.PATCH, newGroup);
            return newGroup;
        }

        String userStoreDomainName = IdentityUtil.extractDomainFromName(oldGroup.getDisplayName());
        if (StringUtils.isNotBlank(userStoreDomainName) && !isSCIMEnabled(userStoreDomainName) &&
                !isInternalOrApplicationGroup(userStoreDomainName)) {
            throw new CharonException("Cannot retrieve group through scim to user store. SCIM is not enabled for user" +
                    " store " + userStoreDomainName);
        }

        String primaryDomain = IdentityUtil.getPrimaryDomainName();

        try {
            String userStoreDomainFromSP = getUserStoreDomainFromSP();
            if (userStoreDomainFromSP != null && !userStoreDomainFromSP
                    .equalsIgnoreCase(IdentityUtil.extractDomainFromName(oldGroup.getDisplayName()))) {
                throw new CharonException("Group :" + oldGroup.getDisplayName() + "is not belong to user store " +
                                          userStoreDomainFromSP + "Hence group updating fail");
            }
        } catch (IdentityApplicationManagementException e) {
            throw new CharonException("Error retrieving User Store name. ", e);
        }

        oldGroup.setDisplayName(IdentityUtil.addDomainToName(UserCoreUtil.removeDomainFromName(oldGroup.getDisplayName()),
                IdentityUtil.extractDomainFromName(oldGroup.getDisplayName())));
        newGroup.setDisplayName(IdentityUtil.addDomainToName(UserCoreUtil.removeDomainFromName(newGroup.getDisplayName()),
                IdentityUtil.extractDomainFromName(newGroup.getDisplayName())));


        if (log.isDebugEnabled()) {
                log.debug("Updating group: " + oldGroup.getDisplayName()); //add from group new name
            }

            /*
             * we need to set the domain name for the newGroup if it doesn't
             * have it
             */
            // we should be able get the domain name like bellow, cause we set
            // it by force at create group

            try {
                /*
                 * set thread local property to signal the downstream
                 * SCIMUserOperationListener
                 * about the provisioning route.
                 */
                SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);

                boolean updated = false;
                /*
                 * set thread local property to signal the downstream
                 * SCIMUserOperationListener
                 * about the provisioning route.
                 */
                SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
                // check if the user ids sent in updated group exist in the user
                // store and the associated user name
                // also a matching one.

                 /* compare user store domain of old group and new group, if there is a mismatch do not allow to
                 patch the group */
                if (IdentityUtil.extractDomainFromName(newGroup.getDisplayName())
                        .equals(primaryDomain) &&
                        !(IdentityUtil.extractDomainFromName(oldGroup.getDisplayName())
                                .equals(primaryDomain))) {
                    String userStoreDomain = IdentityUtil.extractDomainFromName(oldGroup.getDisplayName());
                    newGroup.setDisplayName(
                            IdentityUtil.addDomainToName(newGroup.getDisplayName(), userStoreDomain));
                } else if (!IdentityUtil.extractDomainFromName(oldGroup.getDisplayName())
                        .equals(IdentityUtil.extractDomainFromName(newGroup.getDisplayName()))) {
                    throw new IdentitySCIMException(
                            "User store domain of the group is not matching with the given SCIM group Id.");
                }

                String groupName = newGroup.getDisplayName();
                String userStoreDomainForGroup = IdentityUtil.extractDomainFromName(groupName);

                if (newGroup.getMembers() != null && !newGroup.getMembers().isEmpty() &&
                        !isInternalOrApplicationGroup(userStoreDomainForGroup)) {
                    newGroup = addDomainToUserMembers(newGroup, userStoreDomainForGroup);
                }

                List<String> userIds = newGroup.getMembers();
                List<String> userDisplayNames = newGroup.getMembersWithDisplayName();

                /* compare user store domain of group and user store domain of user name , if there is a mismatch do not
                 allow to patch the group */
                if (userDisplayNames != null && userDisplayNames.size() > 0) {
                    for (String userDisplayName : userDisplayNames) {
                        String userStoreDomainForUser =
                                IdentityUtil.extractDomainFromName(userDisplayName);
                        if (!isInternalOrApplicationGroup(userStoreDomainForGroup) && !userStoreDomainForGroup.equalsIgnoreCase
                                (userStoreDomainForUser)) {
                            throw new IdentitySCIMException(
                                    userDisplayName + " does not " + "belongs to user store " + userStoreDomainForGroup);
                        }

                    }
                }

                String[] userNames = null;
                if (CollectionUtils.isNotEmpty(userIds)) {
                    for (String userId : userIds) {
                        userNames =
                                carbonUM.getUserList(SCIMConstants.ID_URI, userId,
                                                     UserCoreConstants.DEFAULT_PROFILE);
                        if (userNames == null || userNames.length == 0) {
                            String error =
                                    "User: " + userId + " doesn't exist in the user store. " +
                                    "Hence, can not update the group: " + oldGroup.getDisplayName();
                            throw new CharonException(error);
                        } else {
                            if (!UserCoreUtil.isContain(userNames[0],
                                    userDisplayNames.toArray(new String[userDisplayNames.size()]))) {
                                throw new IdentitySCIMException("Given SCIM user Id and name not matching..");
                            }
                        }
                    }
                }

                // we do not update Identity_SCIM DB here since it is updated in
                // SCIMUserOperationListener's methods.

                // update name if it is changed
                if (!(oldGroup.getDisplayName().equalsIgnoreCase(newGroup.getDisplayName()))) {
                    // update group name in carbon UM
                    carbonUM.updateRoleName(oldGroup.getDisplayName(),
                                            newGroup.getDisplayName());

                    updated = true;
                }

                //SCIM request does not have operation attribute for new members need be added hence parsing null
                List<String> addRequestedMembers = newGroup.getMembersWithDisplayName(null);
                List<String> deleteRequestedMembers =
                        newGroup.getMembersWithDisplayName(SCIMConstants.CommonSchemaConstants.OPERATION_DELETE);

                //Handling meta data attributes coming from SCIM request. Through meta attributes all existing members
                // can be replaced with new set of members
                boolean metaDeleteAllMembers = false;
                if (newGroup.getAttributesOfMeta() != null &&
                        SCIMConstants.GroupSchemaConstants.MEMBERS.equals(newGroup.getAttributesOfMeta().get(0))) {
                    if (!deleteRequestedMembers.isEmpty()) {
                        log.warn("All Existing members will be deleted through SCIM meta attributes Hence operation " +
                                "delete is Invalid");
                        deleteRequestedMembers = new ArrayList<>();
                        metaDeleteAllMembers = true;
                    }
                    String users[] = carbonUM.getUserListOfRole(newGroup.getDisplayName());
                    if (addRequestedMembers.isEmpty()) {
                        carbonUM.updateUserListOfRole(newGroup.getDisplayName(), users, new String[0]);
                    } else {
                        //If new set of members contains an old members, save those old members without deleting from user store
                        List<String> membersDeleteFromUserStore = new ArrayList<String>();
                        for (String user : users) {
                            if (!addRequestedMembers.contains(user)) {
                                membersDeleteFromUserStore.add(user);
                            }
                        }
                        carbonUM.updateUserListOfRole(newGroup.getDisplayName(), membersDeleteFromUserStore
                                .toArray(new String[membersDeleteFromUserStore.size()]), new String[0]);
                    }
                }
                // find out added members and deleted members..
                List<String> oldMembers = oldGroup.getMembersWithDisplayName();

                List<String> addedMembers = new ArrayList<>();
                List<String> deletedMembers = new ArrayList<>();

                if (addRequestedMembers.isEmpty() && metaDeleteAllMembers) {
                    String users[] = carbonUM.getUserListOfRole(newGroup.getDisplayName());
                    deletedMembers = Arrays.asList(users);
                }

                for (String addRequestedMember : addRequestedMembers) {
                    if ((!oldMembers.isEmpty()) && oldMembers.contains(addRequestedMember)) {
                        continue;
                    }
                    addedMembers.add(addRequestedMember);
                }

                for (String deleteRequestedMember : deleteRequestedMembers) {
                    if ((!oldMembers.isEmpty()) && oldMembers.contains(deleteRequestedMember)) {
                        deletedMembers.add(deleteRequestedMember);
                    } else {
                        continue;
                    }
                }

                if (newGroup.getDisplayName() != null && ((CollectionUtils.isNotEmpty(addedMembers))
                        || (CollectionUtils.isNotEmpty(deletedMembers)))) {
                    carbonUM.updateUserListOfRole(newGroup.getDisplayName(),
                            deletedMembers.toArray(new String[deletedMembers.size()]),
                            addedMembers.toArray(new String[addedMembers.size()]));
                    updated = true;
                }

                if (updated) {
                    if (log.isDebugEnabled()) {
                        log.debug("Group: " + newGroup.getDisplayName() + " is updated through SCIM.");
                    }
                } else {
                    log.warn("There is no updated field in the group: " + oldGroup.getDisplayName() +
                            ". Therefore ignoring the provisioning.");
                }

            } catch (UserStoreException | IdentitySCIMException e) {
                //throwing real message coming from carbon user manager
                throw new CharonException("Error in patching group", e);
            }
            return newGroup;
    }

    @Override
    public void deleteGroup(String groupId) throws NotFoundException, CharonException {

        //if operating in dumb mode, do not persist the operation, only provision to providers
        if (getServiceProvider(isBulkUserAdd).getInboundProvisioningConfig().isDumbMode()) {
            if (log.isDebugEnabled()) {
                log.debug("This instance is operating in dumb mode. " +
                          "Hence, operation is not persisted, it will only be provisioned.");
            }
            Group group = new Group();
            group.setId(groupId);
            this.provision(ProvisioningOperation.DELETE, group);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Deleting group: " + groupId);
            }
            try {
                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
                SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);

                String groupName;
                SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
                /* Since no userstore domain available at the moment we are using the primary userstore domain for
                getting default configs since we are not encouraging to disable group id enable feature. */
                if (isUniqueGroupIdEnabled(UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME)) {
                    // Get group name by id.
                    org.wso2.carbon.user.core.common.Group retrievedGroup = carbonUM.getGroup(groupId, null);
                    groupName = retrievedGroup.getGroupName();
                } else {
                    //Get group name by id.
                    groupName = groupHandler.getGroupName(groupId);
                }

                if (groupName != null) {
                    String userStoreDomainFromSP = null;
                    try {
                        userStoreDomainFromSP = getUserStoreDomainFromSP();
                    } catch (IdentityApplicationManagementException e) {
                        throw new CharonException("Error retrieving User Store name. ", e);
                    }
                    if (userStoreDomainFromSP != null &&
                        !(userStoreDomainFromSP.equalsIgnoreCase(IdentityUtil.extractDomainFromName(groupName)))) {
                        throw new CharonException("Group :" + groupName + "is not belong to user store " +
                                                  userStoreDomainFromSP + "Hence group updating fail");
                    }

                    String userStoreDomainName = IdentityUtil.extractDomainFromName(groupName);
                    if (!isInternalOrApplicationGroup(userStoreDomainName) && StringUtils.isNotBlank(userStoreDomainName)
                            && !isSCIMEnabled
                            (userStoreDomainName)) {
                        throw new CharonException("Cannot delete group through scim" + ". SCIM is not " +
                                "enabled for user store " + userStoreDomainName);
                    }

                    //delete group in carbon UM
                    carbonUM.deleteRole(groupName);

                    // Since user operation listeners are not fired with operation on Internal roles, handle it in SCIMUserManager
                    if (isInternalOrApplicationGroup(userStoreDomainName)) {
                        groupHandler.deleteGroupAttributes(groupName);
                    }

                    //we do not update Identity_SCIM DB here since it is updated in SCIMUserOperationListener's methods.
                    if (log.isDebugEnabled()) {
                        log.debug("Group: " + groupName + " is deleted through SCIM.");
                    }

                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Group with SCIM id: " + groupId + " doesn't exist in the system.");
                    }
                    throw new NotFoundException();
                }
            } catch (UserStoreException | IdentitySCIMException e) {
                throw new CharonException("Error occurred while deleting group " + groupId, e);
            }
        }
    }

    private User getSCIMUserWithoutRoles(String userName, List<String> claimURIList) throws CharonException {

        claimURIList.add(SCIMConstants.ID_URI);
        claimURIList.add(SCIMConstants.META_CREATED_URI);
        claimURIList.add(SCIMConstants.META_LAST_MODIFIED_URI);
        User scimUser = null;

        try {
            //obtain user claim values
            Map<String, String> attributes = carbonUM.getUserClaimValues(
                    userName, claimURIList.toArray(new String[claimURIList.size()]), null);
            //skip simple type addresses claim coz it is complex with sub types in the schema
            if (attributes.containsKey(SCIMConstants.ADDRESSES_URI)) {
                attributes.remove(SCIMConstants.ADDRESSES_URI);
            }

            //set location uri because location uir is not getting from the userstore
            attributes.put(SCIMConstants.META_LOCATION_URI, SCIMCommonUtils.getSCIMUserURL(userName));

            // Add username with domain name
            attributes.put(SCIMConstants.USER_NAME_URI, userName);
            scimUser = (User) AttributeMapper.constructSCIMObjectFromAttributes(
                    attributes, SCIMConstants.USER_INT);

        } catch (UserStoreException | NotFoundException e) {
            throw new CharonException("Error in getting user information for user: " + userName, e);
        }
        return scimUser;
    }

    private User getSCIMUser(String userName, List<String> claimURIList, String userId) throws CharonException {
        User scimUser = null;

        String userStoreDomainName = IdentityUtil.extractDomainFromName(userName);
        if (StringUtils.isNotBlank(userStoreDomainName) && !isSCIMEnabled(userStoreDomainName)) {
            throw new CharonException("Cannot add user through scim to user store " + ". SCIM is not " +
                    "enabled for user store " + userStoreDomainName);
        }

        try {
            //obtain user claim values
            Map<String, String> userClaimValues = carbonUM.getUserClaimValues(
                    userName, claimURIList.toArray(new String[claimURIList.size()]), null);
            Map<String, String> attributes = SCIMCommonUtils.convertLocalToSCIMDialect(userClaimValues);

            //skip simple type addresses claim coz it is complex with sub types in the schema
            if (attributes.containsKey(SCIMConstants.ADDRESSES_URI)) {
                attributes.remove(SCIMConstants.ADDRESSES_URI);
            }

            // Add username with domain name
            attributes.put(SCIMConstants.USER_NAME_URI, userName);

            //set location uri
            attributes.put(SCIMConstants.META_LOCATION_URI, SCIMCommonUtils.getSCIMUserURL(userId));

            //get groups of user and add it as groups attribute
            String[] roles = carbonUM.getRoleListOfUser(userName);
            //construct the SCIM Object from the attributes
            scimUser = (User) AttributeMapper.constructSCIMObjectFromAttributes(
                    attributes, SCIMConstants.USER_INT);
            //add groups of user:
            for (String role : roles) {
                if (UserCoreUtil.isEveryoneRole(role, carbonUM.getRealmConfiguration())
                        || UserCoreUtil.isPrimaryAdminRole(role, carbonUM.getRealmConfiguration())
                        || CarbonConstants.REGISTRY_ANONNYMOUS_ROLE_NAME.equalsIgnoreCase(role)) {
                    // carbon specific roles do not possess SCIM info, hence
                    // skipping them.
                    continue;
                }
                Group group = getGroupOnlyWithMetaAttributes(role);
                if (group != null) { // can be null for non SCIM groups
                    scimUser.setGroup(null, group.getId(), role);
                }
            }
        } catch (UserStoreException | CharonException | NotFoundException | IdentitySCIMException e) {
            throw new CharonException("Error in getting user information for user: " + userName, e);
        }
        return scimUser;
    }

    /**
     * Get the full group with all the details including users.
     *
     * @param groupName
     * @return
     * @throws CharonException
     * @throws org.wso2.carbon.user.core.UserStoreException
     * @throws IdentitySCIMException
     */
    private Group getGroupWithName(String groupName)
            throws CharonException, org.wso2.carbon.user.core.UserStoreException,
            IdentitySCIMException {

        String userStoreDomainName = IdentityUtil.extractDomainFromName(groupName);
        if (!isInternalOrApplicationGroup(userStoreDomainName) && StringUtils.isNotBlank(userStoreDomainName) &&
                !isSCIMEnabled(userStoreDomainName)) {
            throw new CharonException("Cannot retrieve group through scim to user store " + ". SCIM is not " +
                    "enabled for user store " + userStoreDomainName);
        }

        Group group = new Group();
        if (isUniqueGroupIdEnabled(userStoreDomainName)) {
            org.wso2.carbon.user.core.common.Group retrievedGroup = carbonUM.getGroupByGroupName(groupName, null);
            group = buildGroup(retrievedGroup);
        } else {
            group.setDisplayName(groupName);
            // Get other group attributes and set.
            SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
            group = groupHandler.getGroupWithAttributes(group, groupName);
        }
        String[] userNames = carbonUM.getUserListOfRole(groupName);

        //get the ids of the users and set them in the group with id + display name
        if (userNames != null && userNames.length != 0) {
            for (String userName : userNames) {
                String userId = carbonUM.getUserClaimValue(userName, SCIMConstants.ID_URI, null);
                group.setMember(userId, userName);
            }
        }
        return group;
    }

    /**
     * Build group from user core group object.
     *
     * @param group Group object returned from the user core.
     * @return Group object.
     * @throws CharonException If an error occurred while building the group object.
     */
    private Group buildGroup(org.wso2.carbon.user.core.common.Group group) throws CharonException {

        if (group == null) {
            return null;
        }
        String groupName = group.getGroupName();
        Group scimGroup = new Group();
        scimGroup.setDisplayName(groupName);
        scimGroup.setId(group.getGroupID());
        if (StringUtils.isBlank(group.getLocation())) {
            // Location has not been sent from the user core. Therefore, we need to use the group id to build location.
            scimGroup.setLocation(SCIMCommonUtils.getSCIMGroupURL(group.getGroupID()));
        } else {
            scimGroup.setLocation(group.getLocation());
        }
        // Validate dates.
        if (StringUtils.isNotBlank(group.getCreatedDate())) {
            scimGroup.setCreatedDate(AttributeUtil.parseDateTime(group.getCreatedDate()));
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Group created date is not specified for group: " + groupName);
            }
        }
        if (StringUtils.isNotBlank(group.getLastModifiedDate())) {
            scimGroup.setLastModified(AttributeUtil.parseDateTime(group.getLastModifiedDate()));
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Group last modified date is not specified for group: " + groupName);
            }
        }
        return scimGroup;
    }

    /**
     * Get group with only meta attributes.
     *
     * @param groupName
     * @return
     * @throws CharonException
     * @throws IdentitySCIMException
     * @throws org.wso2.carbon.user.core.UserStoreException
     */
    private Group getGroupOnlyWithMetaAttributes(String groupName)
            throws CharonException, IdentitySCIMException,
            org.wso2.carbon.user.core.UserStoreException {

        //get other group attributes and set.
        String userStoreDomainName = IdentityUtil.extractDomainFromName(groupName);
        if (isUniqueGroupIdEnabled(userStoreDomainName)) {
            org.wso2.carbon.user.core.common.Group retrievedGroup = carbonUM.getGroupByGroupName(groupName, null);
            Group group = buildGroup(retrievedGroup);
            group.setDisplayName(groupName);
            return group;
        } else {
            Group group = new Group();
            group.setDisplayName(groupName);
            SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
            return groupHandler.getGroupWithAttributes(group, groupName);
        }
    }

    /**
     * SCIM dumb mode provisioning flaw start from this method
     *
     * @param provisioningMethod
     * @param provisioningObject
     * @throws CharonException
     */
    private void provision(ProvisioningOperation provisioningMethod, SCIMObject provisioningObject)
            throws CharonException {

        String domainName = UserCoreUtil.getDomainName(carbonUM.getRealmConfiguration());
        try {

            Map<org.wso2.carbon.identity.application.common.model.ClaimMapping, List<String>> outboundAttributes =
                    new HashMap();

            ProvisioningEntity provisioningEntity = null;
            ProvisioningEntityBuilder provisioningEntityBuilder = ProvisioningEntityBuilder.getInstance();
            if (provisioningObject instanceof User) {
                User user = (User) provisioningObject;
                if (ProvisioningOperation.POST.equals(provisioningMethod)) {
                    provisioningEntity =
                            provisioningEntityBuilder
                                    .buildProvisioningEntityForUserAdd(provisioningObject, outboundAttributes,
                                                                       domainName);
                } else if (ProvisioningOperation.DELETE.equals(provisioningMethod)) {
                    provisioningEntity = provisioningEntityBuilder
                            .buildProvisioningEntityForUserDelete(provisioningObject, outboundAttributes,
                                                                  domainName);
                } else if (ProvisioningOperation.PUT.equals(provisioningMethod)) {
                    provisioningEntity = provisioningEntityBuilder
                            .buildProvisioningEntityForUserUpdate(provisioningObject, outboundAttributes,
                                                                  domainName);
                } else if (ProvisioningOperation.PATCH.equals(provisioningMethod)) {
                    provisioningEntity = provisioningEntityBuilder
                            .buildProvisioningEntityForUserPatch(provisioningObject, outboundAttributes,
                                                                 domainName);
                }
            } else if (provisioningObject instanceof Group) {
                Group group = (Group) provisioningObject;
                if (ProvisioningOperation.POST.equals(provisioningMethod)) {
                    provisioningEntity = provisioningEntityBuilder
                            .buildProvisioningEntityForGroupAdd(provisioningObject, outboundAttributes,
                                                                domainName);
                } else if (ProvisioningOperation.DELETE.equals(provisioningMethod)) {
                    provisioningEntity = provisioningEntityBuilder
                            .buildProvisioningEntityForGroupDelete(provisioningObject, outboundAttributes,
                                                                   domainName);
                } else if (ProvisioningOperation.PUT.equals(provisioningMethod)) {
                    provisioningEntity = provisioningEntityBuilder
                            .buildProvisioningEntityForGroupUpdate(provisioningObject, outboundAttributes,
                                                                   domainName);
                } else if (ProvisioningOperation.PATCH.equals(provisioningMethod)) {
                    provisioningEntity =
                            provisioningEntityBuilder
                                    .buildProvisioningEntityForGroupPatch(provisioningObject, outboundAttributes,
                                                                          domainName);
                }
            }

            String tenantDomainName = CarbonContext.getThreadLocalCarbonContext().getTenantDomain();

            ThreadLocalProvisioningServiceProvider threadLocalServiceProvider;
            threadLocalServiceProvider = IdentityApplicationManagementUtil
                    .getThreadLocalProvisioningServiceProvider();

            if (threadLocalServiceProvider != null) {

                String serviceProvider = threadLocalServiceProvider.getServiceProviderName();
                tenantDomainName = threadLocalServiceProvider.getTenantDomain();
                if (threadLocalServiceProvider.getServiceProviderType() == ProvisioningServiceProviderType.OAUTH) {
                    try {
                        serviceProvider = ApplicationManagementService.getInstance()
                                                                      .getServiceProviderNameByClientId(
                                                                              threadLocalServiceProvider
                                                                                      .getServiceProviderName(),
                                                                              "oauth2", tenantDomainName);
                    } catch (IdentityApplicationManagementException e) {
                        log.error("Error while provisioning", e);
                        return;
                    }
                }

                // call framework method to provision the user.
                OutboundProvisioningManager.getInstance().provision(provisioningEntity,
                                                                    serviceProvider,
                                                                    threadLocalServiceProvider.getClaimDialect(),
                                                                    tenantDomainName, threadLocalServiceProvider
                        .isJustInTimeProvisioning());
            } else {
                // call framework method to provision the user.
                OutboundProvisioningManager.getInstance()
                                           .provision(provisioningEntity, ApplicationConstants.LOCAL_SP,
                                                      DefaultInboundUserProvisioningListener.WSO2_CARBON_DIALECT,
                                                      tenantDomainName, false);
            }

        } catch (NotFoundException e) {
            throw new CharonException("Failed to find resource in external user store.", e);
        } catch (IdentityProvisioningException e) {
            throw new CharonException("Error while provisioning to externaluser store in dumb mode.", e);
        } catch (IdentityApplicationManagementException e) {
            throw new CharonException("Could not find dumb mode provisioned entity in DB.", e);
        }
    }

    private String getUserStoreDomainFromSP() throws IdentityApplicationManagementException {

        ThreadLocalProvisioningServiceProvider threadLocalSP = IdentityApplicationManagementUtil
                .getThreadLocalProvisioningServiceProvider();
        ServiceProvider serviceProvider = null;
        if (threadLocalSP.getServiceProviderType() == ProvisioningServiceProviderType.OAUTH) {
            serviceProvider = ApplicationManagementService.getInstance()
                                                          .getServiceProviderByClientId(
                                                                  threadLocalSP.getServiceProviderName(),
                                                                  "oauth2", threadLocalSP.getTenantDomain());
        } else {
            serviceProvider = ApplicationManagementService.getInstance().getServiceProvider(
                    threadLocalSP.getServiceProviderName(), threadLocalSP.getTenantDomain());
        }

        if (serviceProvider != null && serviceProvider.getInboundProvisioningConfig() != null &&
            !StringUtils.isBlank(serviceProvider.getInboundProvisioningConfig().getProvisioningUserStore())) {
            return serviceProvider.getInboundProvisioningConfig().getProvisioningUserStore();
        }
        return null;
    }

    /**
     * This is used to add domain name to the members of a group
     *
     * @param group
     * @param userStoreDomain
     * @return
     * @throws CharonException
     */
    private Group addDomainToUserMembers(Group group, String userStoreDomain) throws CharonException {
        List<String> membersId = group.getMembers();

        if (StringUtils.isBlank(userStoreDomain) || membersId == null || membersId.isEmpty()) {
            return group;
        }

        if (group.isAttributeExist(SCIMConstants.GroupSchemaConstants.MEMBERS)) {
            MultiValuedAttribute members = (MultiValuedAttribute) group.getAttributeList().get(
                    SCIMConstants.GroupSchemaConstants.MEMBERS);
            List<Attribute> attributeValues = members.getValuesAsSubAttributes();

            if (attributeValues != null && !attributeValues.isEmpty()) {
                for (Attribute attributeValue : attributeValues) {
                    SimpleAttribute displayNameAttribute = (SimpleAttribute) attributeValue.getSubAttribute(
                            SCIMConstants.CommonSchemaConstants.DISPLAY);
                    String displayName =
                            AttributeUtil.getStringValueOfAttribute(displayNameAttribute.getValue(),
                                    displayNameAttribute.getDataType());
                    displayNameAttribute.setValue(IdentityUtil.addDomainToName(
                            UserCoreUtil.removeDomainFromName(displayName), userStoreDomain));
                }
            }
        }
        return group;
    }

    /**
     * In current charon implementation there is no way to associate SCIM ID with display name for user member, hence
     * adding association to map
     *
     * @param group
     * @return
     * @throws CharonException
     */
    private Map<String, String> mergeSCIMIDsWithDisplayNames(Group group) throws CharonException {
        List<String> membersId = group.getMembers();
        List<String> membersDisplayNames = group.getMembersWithDisplayName();
        Map<String, String> userMembers = new HashMap<>();
        for (int i = 0; i < membersId.size(); i++) {
            userMembers.put(membersId.get(i), membersDisplayNames.get(i));
        }
        return userMembers;
    }



    private ServiceProvider getServiceProvider(boolean isBulkUserAdd) throws CharonException {

        ThreadLocalProvisioningServiceProvider threadLocalSP = IdentityApplicationManagementUtil
                .getThreadLocalProvisioningServiceProvider();
        //isBulkUserAdd is true indicates bulk user add
        if (isBulkUserAdd) {
            threadLocalSP.setBulkUserAdd(true);
        }
        try {
            if (threadLocalSP.getServiceProviderType() == ProvisioningServiceProviderType.OAUTH) {
                return ApplicationManagementService.getInstance().getServiceProviderByClientId(
                                                           threadLocalSP.getServiceProviderName(),
                                                           "oauth2", threadLocalSP.getTenantDomain());
            } else {
                return ApplicationManagementService.getInstance().getServiceProvider(
                        threadLocalSP.getServiceProviderName(), threadLocalSP.getTenantDomain());
            }
        } catch (IdentityApplicationManagementException e) {
            throw new CharonException("Error retrieving Service Provider. ", e);
        }
    }

    /**
     * This method will return whether SCIM is enabled or not for a particular userstore. (from SCIMEnabled user
     * store property)
     * @param userstoreName user store name
     * @return whether scim is enabled or not for the particular user store
     */
    private boolean isSCIMEnabled(String userstoreName) {
            UserStoreManager userStoreManager = carbonUM.getSecondaryUserStoreManager(userstoreName);
        if (userStoreManager != null) {
            try {
                return userStoreManager.isSCIMEnabled();
            } catch (UserStoreException e) {
                log.error("Error while evaluating isSCIMEnalbed for user store " + userstoreName, e);
            }
        }
        return false;
    }

    /**
     * This method will return whether Unique group id is enabled or not for a particular userstore.
     * @param userstoreName user store name.
     * @return whether Unique group id is enabled or not for the particular user store.
     */
    public boolean isUniqueGroupIdEnabled(String userstoreName) {

        UserStoreManager userStoreManager = carbonUM.getSecondaryUserStoreManager(userstoreName);
        if (userStoreManager != null && userStoreManager instanceof AbstractUserStoreManager) {
            return ((AbstractUserStoreManager) userStoreManager).isUniqueGroupIdEnabled();
        }
        return false;
    }

    /**
     * returns whether particular user store domain is application or internal.
     * @param userstoreDomain user store domain
     * @return whether passed domain name is "internal" or "application"
     */
    private boolean isInternalOrApplicationGroup(String userstoreDomain) {
        if (StringUtils.isNotBlank(userstoreDomain) && (APPLICATION_DOMAIN.equalsIgnoreCase(userstoreDomain) ||
                INTERNAL_DOMAIN.equalsIgnoreCase(userstoreDomain))) {
            return true;
        }
        return false;
    }

    private String getAuthorizedDomainUser(String[] userNames, String authorization) throws CharonException {

        if (ArrayUtils.isEmpty(userNames) || authorization == null) {
            throw new CharonException("Error in getting user information from Carbon User Store");
        }

        String userStoreDomainFromAuthorization = IdentityUtil.extractDomainFromName(authorization);

        if (userNames.length == 1) {
            return userNames[0];
        } else {
            for (String username : userNames) {

                String userStoreDomainFromScimId = IdentityUtil.extractDomainFromName(username);

                if (userStoreDomainFromAuthorization.equals(userStoreDomainFromScimId)) {
                    return username;
                }
            }
        }

        return userNames[0];
    }

    /**
     * Check whether claim is an immutable claim.
     *
     * @param claim claim URI.
     * @return
     */
    private boolean isImmutableClaim(String claim) throws UserStoreException {

        Map<String, String> claimMappings = SCIMCommonUtils.getSCIMtoLocalMappings();

        return claim.equals(claimMappings.get(SCIMConstants.ID_URI)) || claim.equals(claimMappings.get(SCIMConstants
                .USER_NAME_URI)) || claim.equals(claimMappings.get(SCIMConstants.ROLES_URI)) || claim.equals
                (claimMappings.get(SCIMConstants.META_CREATED_URI)) || claim.equals(claimMappings.get(SCIMConstants
                .META_LAST_MODIFIED_URI)) || claim.equals(claimMappings.get(SCIMConstants.META_LOCATION_URI)) ||
                claim.equals(claimMappings.get(SCIMConstants.NAME_FAMILY_NAME_URI)) || claim.equals(claimMappings
                .get(SCIMConstants.GROUPS_URI)) || claim.contains(UserCoreConstants.ClaimTypeURIs.IDENTITY_CLAIM_URI);
    }
}

