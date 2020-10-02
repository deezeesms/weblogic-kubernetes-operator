// Copyright (c) 2017, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.rest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.json.JsonPatchBuilder;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.credentials.AccessTokenAuthentication;
import oracle.kubernetes.operator.TuningParameters;
import oracle.kubernetes.operator.TuningParameters.CallBuilderTuning;
import oracle.kubernetes.operator.builders.CallParamsImpl;
import oracle.kubernetes.operator.calls.RequestParams;
import oracle.kubernetes.operator.calls.SynchronousCallFactory;
import oracle.kubernetes.operator.helpers.AuthorizationProxy.Operation;
import oracle.kubernetes.weblogic.domain.api.WeblogicApi;
import oracle.kubernetes.weblogic.domain.model.Domain;
import oracle.kubernetes.weblogic.domain.model.DomainList;
import org.apache.commons.lang.ArrayUtils;


/**
 * RestBackendImpl implements the backend of the WebLogic operator REST api by making calls to
 * Kubernetes and WebLogic. A separate instance is created for each REST request since we need to
 * hold some per-request state.
 */
public class DedicatedModeRestBackendImpl extends RestBackendImpl {

  private RestUserCallBuilder callBuilder;

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
    callBuilder = new RestUserCallBuilderFactory().create(createApiClient(accessToken));
    System.out.println("DedicatedModeRestBackendImpl callBuilder: " + callBuilder);
    LOGGER.exiting();
  }

  private ApiClient createApiClient(String accessToken) {
    LOGGER.entering();
    AccessTokenAuthentication authentication = new AccessTokenAuthentication(accessToken);
    ClientBuilder builder = null;
    try {
      builder = ClientBuilder.standard();
      ApiClient apiClient = builder.setAuthentication(authentication).build();
      return apiClient;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected void authorize(String domainUid, Operation operation) {
    // no op
  }

  protected List<Domain> getDomainsList() {
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

  private static class RestUserCallBuilder {
    private final ApiClient client;

    private Integer limit = 50;
    private Integer timeoutSeconds = 5;
    private final CallParamsImpl callParams = new CallParamsImpl();

    private final String resourceVersion = "";

    /* Domains */
    private Integer maxRetryCount = 10;
    private final Boolean watch = Boolean.FALSE;

    private String fieldSelector;
    private String labelSelector;

    private final String pretty = "false";

    private static final SynchronousApiClientCallDispatcher DEFAULT_DISPATCHER =
        new SynchronousApiClientCallDispatcher() {
          @Override
          public <T> T execute(
              SynchronousCallFactory<T> factory, RequestParams params, ApiClient client)
              throws ApiException {
            return factory.execute(client, params);
          }
        };

    private static SynchronousApiClientCallDispatcher DISPATCHER = DEFAULT_DISPATCHER;

    private final SynchronousCallFactory<Domain> patchDomainCall =
        (client, requestParams) ->
            new WeblogicApi(client)
                .patchNamespacedDomain(
                    requestParams.name, requestParams.namespace, (V1Patch) requestParams.body);
    private final SynchronousCallFactory<DomainList> listDomainCall =
        (client, requestParams) ->
            new WeblogicApi(client)
                .listNamespacedDomain(
                    requestParams.namespace,
                    pretty,
                    null,
                    fieldSelector,
                    labelSelector,
                    limit,
                    resourceVersion,
                    timeoutSeconds,
                    watch);

    public RestUserCallBuilder(ApiClient client) {
      this(getCallBuilderTuning(), client);
    }

    private RestUserCallBuilder(CallBuilderTuning tuning, ApiClient client) {
      if (tuning != null) {
        tuning(tuning.callRequestLimit, tuning.callTimeoutSeconds, tuning.callMaxRetryCount);
      }
      this.client = client;
    }

    /**
     * Patch domain.
     *
     * @param uid the domain uid (unique within the k8s cluster)
     * @param namespace the namespace containing the domain
     * @param patchBody the patch to apply
     * @return Updated domain
     * @throws ApiException APIException
     */
    public Domain patchDomain(String uid, String namespace, V1Patch patchBody) throws ApiException {
      RequestParams requestParams =
          new RequestParams("patchDomain", namespace, uid, patchBody, uid);
      return executeSynchronousCall(requestParams, patchDomainCall);
    }

    /**
     * List domains.
     *
     * @param namespace Namespace
     * @return Domain list
     * @throws ApiException API exception
     */
    public DomainList listDomain(String namespace) throws ApiException {
      RequestParams requestParams = new RequestParams("listDomain", namespace, null, null, callParams);
      return executeSynchronousCall(requestParams, listDomainCall);
    }

    /**
     * Creates instance that will acquire clients as needed from the {@link ApiClient} instance.
     *
     * @param tuning Tuning parameters
     * @return Call builder
     */
    static RestUserCallBuilder create(CallBuilderTuning tuning, ApiClient client) {
      return new RestUserCallBuilder(tuning, client);
    }

    private <T> T executeSynchronousCall(
        RequestParams requestParams, SynchronousCallFactory<T> factory) throws ApiException {
      return DISPATCHER.execute(factory, requestParams, client);
    }

    /**
     * Consumer for label selectors.
     * @param selectors Label selectors
     * @return this CallBuilder
     */
    public RestUserCallBuilder withLabelSelectors(String... selectors) {
      this.labelSelector = !ArrayUtils.isEmpty(selectors) ? String.join(",", selectors) : null;
      return this;
    }

    public RestUserCallBuilder withFieldSelector(String fieldSelector) {
      this.fieldSelector = fieldSelector;
      return this;
    }

    private void tuning(int limit, int timeoutSeconds, int maxRetryCount) {
      this.limit = limit;
      this.timeoutSeconds = timeoutSeconds;
      this.maxRetryCount = maxRetryCount;

      this.callParams.setLimit(limit);
      this.callParams.setTimeoutSeconds(timeoutSeconds);
    }

    private static CallBuilderTuning getCallBuilderTuning() {
      return Optional.ofNullable(TuningParameters.getInstance())
          .map(TuningParameters::getCallBuilderTuning)
          .orElse(null);
    }
  }

  private static class RestUserCallBuilderFactory {
    private final TuningParameters tuning;

    public RestUserCallBuilderFactory() {
      this.tuning = TuningParameters.getInstance();
    }

    public RestUserCallBuilder create(ApiClient client) {
      return RestUserCallBuilder.create(tuning != null ? tuning.getCallBuilderTuning() : null, client);
    }
  }

  private interface SynchronousApiClientCallDispatcher {
    <T> T execute(
        SynchronousCallFactory<T> factory, RequestParams requestParams, ApiClient client)
        throws ApiException;
  }
}
