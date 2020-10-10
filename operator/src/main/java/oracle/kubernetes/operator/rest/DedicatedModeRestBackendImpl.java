// Copyright (c) 2017, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.rest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import javax.json.JsonPatchBuilder;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import oracle.kubernetes.operator.helpers.AuthorizationProxy.Operation;
import oracle.kubernetes.operator.helpers.CallBuilder;
import oracle.kubernetes.operator.helpers.CallBuilderFactory;
import oracle.kubernetes.operator.helpers.ClientPool;
import oracle.kubernetes.weblogic.domain.model.Domain;
import oracle.kubernetes.weblogic.domain.model.DomainList;


/**
 * RestBackendImpl implements the backend of the WebLogic operator REST api by making calls to
 * Kubernetes and WebLogic. A separate instance is created for each REST request since we need to
 * hold some per-request state.
 */
public class DedicatedModeRestBackendImpl extends RestBackendImpl {

  private CallBuilder callBuilder;

  /**
   * Construct a RestBackendImpl that is used to handle one WebLogic operator REST request.
   *
   * @param accessToken is the access token of the Kubernetes service account of the client calling
   *     the WebLogic operator REST api.
   * @param domainNamespaces a list of Kubernetes namepaces that contain domains that the WebLogic
   *     operator manages.
   */
  DedicatedModeRestBackendImpl(String accessToken, Collection<String> domainNamespaces) {
    super(domainNamespaces);
    LOGGER.entering(domainNamespaces);
    ClientPool pool = new ClientPool();
    pool.setApiClient(createApiClient(accessToken));
    callBuilder = new CallBuilderFactory().create(pool);
    System.out.println("DedicatedModeRestBackendImpl callBuilder: " + callBuilder);
    LOGGER.exiting();
  }

  protected void authorize(String domainUid, Operation operation) {
    // no op
  }

  protected List<Domain> getDomainsList() {
    System.out.println("DedicatedModeRestBackendImpl getDomainsList");
    Collection<List<Domain>> c = new ArrayList<>();
    try {
      for (String ns : domainNamespaces) {
        DomainList dl = callBuilder.listDomain(ns);

        if (dl != null) {
          c.add(dl.getItems());
        }
      }
      return c.stream().flatMap(Collection::stream).collect(Collectors.toList());
    } catch (ApiException e) {
      throw handleApiException(e);
    }
  }

  protected void patchDomain(Domain domain, JsonPatchBuilder patchBuilder) {
    System.out.println("DedicatedModeRestBackendImpl patchDomain");
    try {
      callBuilder.patchDomain(
              domain.getDomainUid(), domain.getMetadata().getNamespace(),
              new V1Patch(patchBuilder.build().toString()));
    } catch (ApiException e) {
      throw handleApiException(e);
    }
  }
}
