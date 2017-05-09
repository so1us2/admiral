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

package com.vmware.admiral.auth.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.vmware.admiral.auth.project.ProjectRolesHandler.ProjectRoles;
import com.vmware.admiral.auth.util.ProjectUtil;
import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.AssertUtil;
import com.vmware.admiral.common.util.AuthUtils;
import com.vmware.admiral.common.util.PropertyUtils;
import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.common.util.ServiceDocumentQuery;
import com.vmware.photon.controller.model.ServiceUtils;
import com.vmware.photon.controller.model.resources.ResourceState;
import com.vmware.xenon.common.DeferredResult;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Operation.AuthorizationContext;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceDocumentDescription;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.QueryTask.Query;
import com.vmware.xenon.services.common.ServiceUriPaths;
import com.vmware.xenon.services.common.UserGroupService;
import com.vmware.xenon.services.common.UserGroupService.UserGroupState;
import com.vmware.xenon.services.common.UserService.UserState;

/**
 * Project is a group sharing same resources.
 */
public class ProjectService extends StatefulService {

    public static final String FACTORY_LINK = ManagementUriParts.PROJECTS;
    public static final String DEFAULT_PROJECT_ID = "default-project";
    public static final String DEFAULT_PROJECT_LINK = UriUtils.buildUriPath(FACTORY_LINK,
            DEFAULT_PROJECT_ID);

    public static ProjectState buildDefaultProjectInstance() {
        ProjectState project = new ProjectState();
        project.documentSelfLink = DEFAULT_PROJECT_LINK;
        project.name = DEFAULT_PROJECT_ID;
        project.id = project.name;

        return project;
    }

    /**
     * This class represents the document state associated with a
     * {@link com.vmware.admiral.auth.project.ProjectService}.
     */
    public static class ProjectState extends ResourceState {
        public static final String FIELD_NAME_PUBLIC = "isPublic";
        public static final String FIELD_NAME_DESCRIPTION = "description";

        @Documentation(description = "Used for define a public project")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL,
                PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public boolean isPublic;

        @Documentation(description = "Used for descripe the purpose of the project")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL,
                PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public String description;

        /** Link to the group of administrators for this project. */
        @Documentation(description = "Link to the group of administrators for this project.")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.LINK,
                PropertyUsageOption.SINGLE_ASSIGNMENT, PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public String administratorsUserGroupLink;

        /** Link to the group of members for this project. */
        @Documentation(description = "Link to the group of members for this project.")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.LINK,
                PropertyUsageOption.SINGLE_ASSIGNMENT, PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public String membersUserGroupLink;

        public void copyTo(ProjectState destination) {
            super.copyTo(destination);
            destination.isPublic = this.isPublic;
            destination.description = this.description;
            destination.administratorsUserGroupLink = this.administratorsUserGroupLink;
            destination.membersUserGroupLink = this.membersUserGroupLink;
        }

        public static ProjectState copyOf(ProjectState source) {
            if (source == null) {
                return null;
            }

            ProjectState result = new ProjectState();
            source.copyTo(result);
            return result;
        }
    }

    /**
     * This class represents the expanded document state associated with a
     * {@link com.vmware.admiral.auth.project.ProjectService}.
     */
    public static class ProjectStateWithMembers extends ProjectState {

        /** List of administrators for this project. */
        @Documentation(description = "List of administrators for this project.")
        public List<UserState> administrators;

        /** List of members for this project. */
        @Documentation(description = "List of members for this project.")
        public List<UserState> members;

        public void copyTo(ProjectStateWithMembers destination) {
            super.copyTo(destination);
            if (administrators != null) {
                destination.administrators = new ArrayList<>(administrators);
            }
            if (members != null) {
                destination.members = new ArrayList<>(members);
            }
        }
    }

    public ProjectService() {
        super(ProjectState.class);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.IDEMPOTENT_POST, true);
    }

    @Override
    public void handleCreate(Operation post) {
        if (!checkForBody(post)) {
            return;
        }

        ProjectState createBody = post.getBody(ProjectState.class);
        validateState(createBody);

        if (AuthUtils.isDevOpsAdmin(post)) {
            createAdminAndMemberGroups(createBody, post.getAuthorizationContext())
                    .thenAccept((projectState) -> {
                        post.setBody(projectState);
                    })
                    .whenCompleteNotify(post);
        } else {
            post.complete();
        }
    }

    @Override
    public void handleGet(Operation get) {
        if (UriUtils.hasODataExpandParamValue(get.getUri())) {
            retrieveExpandedState(getState(get), get);
        } else {
            super.handleGet(get);
        }
    }

    @Override
    public void handlePut(Operation put) {
        if (!checkForBody(put)) {
            return;
        }

        ProjectState projectPut = put.getBody(ProjectState.class);
        ProjectRoles rolesPut = put.getBody(ProjectRoles.class);
        if (rolesPut.administrators != null || rolesPut.members != null) {
            // this is an update of the roles
            new ProjectRolesHandler(getHost(), getSelfLink()).handleRolesUpdate(rolesPut)
                    .thenAccept((ignore) -> put.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED))
                    .whenCompleteNotify(put);
        } else {
            // this is an update of the state
            validateState(projectPut);
            this.setState(put, projectPut);
            put.setBody(projectPut).complete();
            return;
        }
    }

    @Override
    public void handlePatch(Operation patch) {
        if (!patch.hasBody()) {
            patch.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
            patch.complete();
            return;
        }

        // Patch project state properties
        ProjectState projectPatch = patch.getBody(ProjectState.class);
        if (projectPatch != null) {
            boolean stateModified = handleProjectPatch(getState(patch), projectPatch);
            if (!stateModified) {
                // if the signature hasn't change we shouldn't modify the state
                patch.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
            }
        } else {
            patch.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
        }

        // Patch roles
        ProjectRoles rolesPatch = patch.getBody(ProjectRoles.class);
        if (rolesPatch != null) {
            new ProjectRolesHandler(getHost(), getSelfLink()).handleRolesUpdate(rolesPatch)
                    .whenCompleteNotify(patch);
        } else {
            patch.complete();
        }

    }

    /** Returns whether the projects state signature was changed after the patch. */
    private boolean handleProjectPatch(ProjectState currentState, ProjectState patchState) {
        ServiceDocumentDescription docDesc = getDocumentTemplate().documentDescription;
        String currentSignature = Utils.computeSignature(currentState, docDesc);

        PropertyUtils.mergeServiceDocuments(currentState, patchState);
        String newSignature = Utils.computeSignature(currentState, docDesc);

        return !currentSignature.equals(newSignature);
    }

    @Override
    public void handleDelete(Operation delete) {
        ProjectState state = getState(delete);
        if (state == null || state.documentSelfLink == null) {
            delete.complete();
            return;
        }

        QueryTask queryTask = ProjectUtil.createQueryTaskForProjectAssociatedWithPlacement(state,
                null);

        sendRequest(Operation.createPost(getHost(), ServiceUriPaths.CORE_QUERY_TASKS)
                .setBody(queryTask)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        logSevere("Failed to retrieve placements associated with project: "
                                + state.documentSelfLink);
                        delete.fail(e);
                        return;
                    } else {
                        ServiceDocumentQueryResult result = o.getBody(QueryTask.class).results;
                        long documentCount = result.documentCount;
                        if (documentCount != 0) {
                            delete.fail(new LocalizableValidationException(
                                    ProjectUtil.PROJECT_IN_USE_MESSAGE,
                                    ProjectUtil.PROJECT_IN_USE_MESSAGE_CODE,
                                    documentCount, documentCount > 1 ? "s" : ""));
                            return;
                        }

                        super.handleDelete(delete);
                    }
                }));
    }

    @Override
    public ServiceDocument getDocumentTemplate() {
        ProjectState template = (ProjectState) super.getDocumentTemplate();
        ServiceUtils.setRetentionLimit(template);

        template.name = "resource-group-1";
        template.id = "project-id";
        template.description = "project1";
        template.isPublic = true;

        return template;
    }

    /**
     * Creates a {@link ProjectStateWithMembers} based on the current state of the service by
     * additionally building the lists of administrators and members. When done, the prepared
     * expanded state will be set as body for the provided <code>get</code> {@link Operation} and it
     * will be completed.
     */
    private void retrieveExpandedState(ProjectState simpleState, Operation get) {
        ProjectStateWithMembers expandedState = new ProjectStateWithMembers();
        simpleState.copyTo(expandedState);

        DeferredResult<Void> retrieveAdmins = retrieveUserGroupMembers(
                simpleState.administratorsUserGroupLink)
                        .thenAccept((adminsList) -> expandedState.administrators = adminsList);
        DeferredResult<Void> retrieveMembers = retrieveUserGroupMembers(
                simpleState.membersUserGroupLink)
                        .thenAccept((membersList) -> expandedState.members = membersList);

        DeferredResult.allOf(retrieveAdmins, retrieveMembers)
                .thenAccept((ignore) -> get.setBody(expandedState))
                .whenCompleteNotify(get);
    }

    /**
     * Retrieves the list of members for the specified by document link user group.
     *
     * @see #retrieveUserStatesForGroup(UserGroupState)
     */
    private DeferredResult<List<UserState>> retrieveUserGroupMembers(String groupLink) {
        if (groupLink == null) {
            return DeferredResult.completed(new ArrayList<>(0));
        }

        Operation groupGet = Operation.createGet(getHost(), groupLink).setReferer(getUri());
        return getHost().sendWithDeferredResult(groupGet, UserGroupState.class)
                .thenCompose(this::retrieveUserStatesForGroup);
    }

    /**
     * Retrieves the list of members for the specified user group.
     */
    private DeferredResult<List<UserState>> retrieveUserStatesForGroup(UserGroupState groupState) {
        DeferredResult<List<UserState>> deferredResult = new DeferredResult<>();
        ArrayList<UserState> resultList = new ArrayList<>();

        QueryTask queryTask = QueryUtil.buildQuery(UserState.class, true, groupState.query);
        QueryUtil.addExpandOption(queryTask);
        new ServiceDocumentQuery<UserState>(getHost(), UserState.class)
                .query(queryTask, (r) -> {
                    if (r.hasException()) {
                        logWarning("Failed to retrieve members of UserGroupState %s: %s",
                                groupState.documentSelfLink, Utils.toString(r.getException()));
                        deferredResult.fail(r.getException());
                    } else if (r.hasResult()) {
                        resultList.add(r.getResult());
                    } else {
                        deferredResult.complete(resultList);
                    }
                });

        return deferredResult;
    }

    private void validateState(ProjectState state) {
        Utils.validateState(getStateDescription(), state);
        AssertUtil.assertNotNullOrEmpty(state.name, ProjectState.FIELD_NAME_NAME);
    }

    private DeferredResult<ProjectState> createAdminAndMemberGroups(ProjectState projectState,
            AuthorizationContext authContext) {

        if (projectState.administratorsUserGroupLink != null
                && projectState.membersUserGroupLink != null
                && !projectState.administratorsUserGroupLink.trim().isEmpty()
                && !projectState.membersUserGroupLink.trim().isEmpty()) {
            // No groups to create
            return DeferredResult.completed(projectState);
        }

        ArrayList<DeferredResult<Void>> deferredResults = new ArrayList<>();
        Query defaultQuery = createDefaultUserGroupQuery(authContext);

        if (projectState.administratorsUserGroupLink == null
                || projectState.administratorsUserGroupLink.trim().isEmpty()) {
            deferredResults.add(getHost()
                    .sendWithDeferredResult(buildCreateUserGroupOperation(defaultQuery),
                            UserGroupState.class)
                    .thenAccept((groupState) -> {
                        projectState.administratorsUserGroupLink = groupState.documentSelfLink;
                    }));
        }
        if (projectState.membersUserGroupLink == null
                || projectState.membersUserGroupLink.trim().isEmpty()) {
            deferredResults.add(getHost()
                    .sendWithDeferredResult(buildCreateUserGroupOperation(defaultQuery),
                            UserGroupState.class)
                    .thenAccept((groupState) -> {
                        projectState.membersUserGroupLink = groupState.documentSelfLink;
                    }));
        }

        return DeferredResult.allOf(deferredResults)
                .thenCompose((ignore) -> DeferredResult.completed(projectState));
    }

    private Operation buildCreateUserGroupOperation(Query userGroupQuery) {
        UserGroupState userGroupState = UserGroupState.Builder
                .create()
                .withQuery(userGroupQuery)
                .build();

        return Operation.createPost(getHost(), UserGroupService.FACTORY_LINK)
                .setReferer(getHost().getUri())
                .setBody(userGroupState);
    }

    private Query createDefaultUserGroupQuery(AuthorizationContext authContext) {
        return AuthUtils
                .buildUsersQuery(Collections.singletonList(getAuthorizedUserLink(authContext)));
    }

    private String getAuthorizedUserLink(AuthorizationContext authContext) {
        return authContext.getClaims().getSubject();
    }

}
