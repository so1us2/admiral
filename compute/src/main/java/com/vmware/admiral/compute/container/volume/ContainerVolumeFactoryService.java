/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.compute.container.volume;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.OperationUtil;
import com.vmware.admiral.compute.container.volume.ContainerVolumeService.ContainerVolumeState;
import com.vmware.admiral.service.common.AbstractSecuredFactoryService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;

public class ContainerVolumeFactoryService extends AbstractSecuredFactoryService {
    public static final String SELF_LINK = ManagementUriParts.CONTAINER_VOLUMES;

    public ContainerVolumeFactoryService() {
        super(ContainerVolumeState.class);
    }

    @Override
    public Service createServiceInstance() throws Throwable {
        return new ContainerVolumeService();
    }

    @Override
    public void handleGet(Operation get) {
        OperationUtil.transformProjectHeaderToFilterQuery(get);
        super.handleGet(get);
    }
}
