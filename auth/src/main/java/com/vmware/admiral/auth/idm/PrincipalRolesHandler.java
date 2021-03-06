/*
 * Copyright (c) 2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.auth.idm;

import static com.vmware.admiral.auth.util.AuthUtil.CLOUD_ADMINS_USER_GROUP_LINK;
import static com.vmware.admiral.auth.util.AuthUtil.addReplicationFactor;
import static com.vmware.admiral.auth.util.AuthUtil.buildCloudAdminsRole;
import static com.vmware.admiral.auth.util.PrincipalUtil.encode;
import static com.vmware.admiral.common.util.AssertUtil.assertNotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.vmware.admiral.auth.idm.Principal.PrincipalType;
import com.vmware.admiral.auth.util.AuthUtil;
import com.vmware.admiral.auth.util.PrincipalUtil;
import com.vmware.admiral.auth.util.UserGroupsUpdater;
import com.vmware.xenon.common.DeferredResult;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.ServiceHost.ServiceNotFoundException;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.services.common.RoleService;
import com.vmware.xenon.services.common.RoleService.RoleState;

public class PrincipalRolesHandler {

    private static final String ROLE_NOT_SUPPORTED_MESSAGE = "Assign/unassign operations for role"
            + " %s not supported.";
    private static final String ROLE_NOT_SUPPORTED_MESSAGE_CODE = "auth.role.not.supported";

    public static class PrincipalRoleAssignment {
        public List<String> add;
        public List<String> remove;
    }

    private Service service;
    private PrincipalRoleAssignment roleAssignment;
    private String principalId;

    private PrincipalRolesHandler() {
    }

    private PrincipalRolesHandler(ServiceHost host, PrincipalRoleAssignment roleAssignment,
            String principalId) {
        this.setService(service);
        this.setPrincipalId(principalId);
    }

    public static PrincipalRolesHandler create() {
        return new PrincipalRolesHandler();
    }

    public static boolean isPrincipalRolesUpdate(Operation op) {
        PrincipalRoleAssignment body = op.getBody(PrincipalRoleAssignment.class);
        if (body == null) {
            return false;
        }
        return (body.add != null && !body.add.isEmpty())
                || (body.remove != null && !body.remove.isEmpty());
    }

    public PrincipalRolesHandler setService(Service service) {
        assertNotNull(service, "service");
        this.service = service;
        return this;
    }

    public PrincipalRolesHandler setRoleAssignment(PrincipalRoleAssignment roleAssignment) {
        assertNotNull(roleAssignment, "roleAssignment");
        this.roleAssignment = roleAssignment;
        return this;
    }

    public PrincipalRolesHandler setPrincipalId(String principalId) {
        assertNotNull(principalId, "principalId");
        this.principalId = principalId;
        return this;
    }

    public DeferredResult<Void> update() {
        if ((roleAssignment.add == null || roleAssignment.add.isEmpty())
                && (roleAssignment.remove == null || roleAssignment.remove.isEmpty())) {
            return DeferredResult.completed(null);
        }

        return PrincipalUtil.getPrincipal(service, encode(principalId))
                .thenCompose(principal -> {
                    if (principal.type == PrincipalType.GROUP) {
                        return handleUserGroup();
                    }
                    return handleUser();
                });
    }

    private DeferredResult<Void> handleUser() {
        List<DeferredResult<Void>> results = new ArrayList<>();
        if (roleAssignment.add != null && !roleAssignment.add.isEmpty()) {
            for (String role : roleAssignment.add) {
                AuthRole authRole = AuthRole.valueOf(role);
                results.add(handleUserRoleAssignment(authRole));
            }
        }

        if (roleAssignment.remove != null && !roleAssignment.remove.isEmpty()) {
            for (String role : roleAssignment.remove) {
                AuthRole authRole = AuthRole.valueOf(role);
                results.add(handleUserRoleUnassignment(authRole));
            }
        }

        return DeferredResult.allOf(results).thenAccept(ignore -> {
        });
    }

    private DeferredResult<Void> handleUserGroup() {
        List<DeferredResult<Void>> results = new ArrayList<>();
        if (roleAssignment.add != null && !roleAssignment.add.isEmpty()) {
            for (String role : roleAssignment.add) {
                AuthRole authRole = AuthRole.valueOf(role);
                results.add(handleUserGroupRoleAssignment(authRole));
            }
        }

        if (roleAssignment.remove != null && !roleAssignment.remove.isEmpty()) {
            for (String role : roleAssignment.remove) {
                AuthRole authRole = AuthRole.valueOf(role);
                results.add(handleUserGroupRoleUnassignment(authRole));
            }
        }

        return DeferredResult.allOf(results).thenAccept(ignore -> {
        });
    }

    private DeferredResult<Void> handleUserRoleAssignment(AuthRole role) {

        if (role != AuthRole.CLOUD_ADMIN) {
            return DeferredResult.failed(new LocalizableValidationException(
                    ROLE_NOT_SUPPORTED_MESSAGE, ROLE_NOT_SUPPORTED_MESSAGE_CODE, role.name()));
        }

        return PrincipalUtil.getOrCreateUser(service, principalId)
                .thenCompose(user -> UserGroupsUpdater.create()
                        .setService(service)
                        .setGroupLink(AuthUtil.CLOUD_ADMINS_USER_GROUP_LINK)
                        .setUsersToAdd(Collections.singletonList(principalId))
                        .update());
    }

    private DeferredResult<Void> handleUserRoleUnassignment(AuthRole role) {

        if (role == AuthRole.CLOUD_ADMIN) {
            return UserGroupsUpdater.create()
                    .setService(service)
                    .setGroupLink(CLOUD_ADMINS_USER_GROUP_LINK)
                    .setUsersToRemove(Collections.singletonList(principalId))
                    .update();

        }
        return DeferredResult.failed(new LocalizableValidationException(
                ROLE_NOT_SUPPORTED_MESSAGE, ROLE_NOT_SUPPORTED_MESSAGE_CODE, role.name()));

    }

    private DeferredResult<Void> handleUserGroupRoleAssignment(AuthRole role) {
        if (role == AuthRole.CLOUD_ADMIN) {
            return handleCloudAdminGroupAssignment(principalId);
        }

        return DeferredResult.failed(new LocalizableValidationException(
                ROLE_NOT_SUPPORTED_MESSAGE, ROLE_NOT_SUPPORTED_MESSAGE_CODE, role.name()));

    }

    private DeferredResult<Void> handleUserGroupRoleUnassignment(AuthRole role) {
        if (role == AuthRole.CLOUD_ADMIN) {
            return handleCloudAdminGroupUnassignment();
        }

        return DeferredResult.failed(new LocalizableValidationException(
                ROLE_NOT_SUPPORTED_MESSAGE, ROLE_NOT_SUPPORTED_MESSAGE_CODE, role.name()));

    }

    private DeferredResult<Void> handleCloudAdminGroupUnassignment() {
        String roleLink = UriUtils.buildUriPath(RoleService.FACTORY_LINK, AuthRole.CLOUD_ADMIN
                .buildRoleWithSuffix(encode(principalId)));
        Operation getRole = Operation.createGet(service, roleLink);

        DeferredResult<Void> result = new DeferredResult<>();

        service.sendWithDeferredResult(getRole, RoleState.class)
                .handle((ug, ex) -> {
                    if (ex != null) {
                        if (ex.getCause() instanceof ServiceNotFoundException) {
                            // User group was never assigned, so we don't have to delete any role.
                            DeferredResult.completed(ug);
                        }
                        return DeferredResult.failed(ex);
                    }
                    Operation deleteRoleOp = Operation.createDelete(service, roleLink);
                    addReplicationFactor(deleteRoleOp);
                    return service.sendWithDeferredResult(deleteRoleOp, RoleState.class);
                })
                .thenAccept(ignore -> result.complete(null))
                .exceptionally(ex -> {
                    result.fail(ex);
                    return null;
                });

        return result;
    }

    private DeferredResult<Void> handleCloudAdminGroupAssignment(String principalId) {
        return PrincipalUtil.getOrCreateUserGroup(service, principalId)
                .thenCompose(userGroup -> {
                    RoleState roleState = buildCloudAdminsRole(encode(principalId),
                            userGroup.documentSelfLink);
                    Operation createRoleOp = Operation
                            .createPost(service, RoleService.FACTORY_LINK)
                            .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_FORCE_INDEX_UPDATE)
                            .setBody(roleState);
                    addReplicationFactor(createRoleOp);
                    return service.sendWithDeferredResult(createRoleOp, RoleState.class);
                }).thenAccept(ignore -> {
                });
    }
}
