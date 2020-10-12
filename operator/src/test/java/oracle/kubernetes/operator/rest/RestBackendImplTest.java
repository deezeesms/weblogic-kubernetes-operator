// Copyright (c) 2018, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.rest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.ws.rs.WebApplicationException;

import com.meterware.simplestub.Memento;
import com.meterware.simplestub.StaticStubSupport;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.auth.ApiKeyAuth;
import io.kubernetes.client.openapi.auth.Authentication;
import io.kubernetes.client.openapi.models.V1LocalSubjectAccessReview;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1SubjectAccessReview;
import io.kubernetes.client.openapi.models.V1SubjectAccessReviewStatus;
import io.kubernetes.client.openapi.models.V1TokenReview;
import io.kubernetes.client.openapi.models.V1TokenReviewStatus;
import io.kubernetes.client.openapi.models.V1UserInfo;
import oracle.kubernetes.operator.helpers.AuthorizationProxy;
import oracle.kubernetes.operator.helpers.CallBuilder;
import oracle.kubernetes.operator.helpers.ClientPool;
import oracle.kubernetes.operator.helpers.KubernetesTestSupport;
import oracle.kubernetes.operator.rest.RestBackendImpl.TopologyRetriever;
import oracle.kubernetes.operator.rest.backend.RestBackend;
import oracle.kubernetes.operator.rest.model.DomainAction;
import oracle.kubernetes.operator.rest.model.DomainActionType;
import oracle.kubernetes.operator.utils.WlsDomainConfigSupport;
import oracle.kubernetes.operator.wlsconfig.WlsDomainConfig;
import oracle.kubernetes.utils.TestUtils;
import oracle.kubernetes.weblogic.domain.ClusterConfigurator;
import oracle.kubernetes.weblogic.domain.DomainConfigurator;
import oracle.kubernetes.weblogic.domain.DomainConfiguratorFactory;
import oracle.kubernetes.weblogic.domain.model.Domain;
import oracle.kubernetes.weblogic.domain.model.DomainSpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static oracle.kubernetes.operator.helpers.KubernetesTestSupport.DOMAIN;
import static oracle.kubernetes.operator.helpers.KubernetesTestSupport.LOCAL_SUBJECT_ACCESS_REVIEW;
import static oracle.kubernetes.operator.helpers.KubernetesTestSupport.SUBJECT_ACCESS_REVIEW;
import static oracle.kubernetes.operator.helpers.KubernetesTestSupport.TOKEN_REVIEW;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertNull;

@SuppressWarnings("SameParameterValue")
public class RestBackendImplTest {

  private static final int REPLICA_LIMIT = 4;
  private static final String NS = "namespace1";
  private static final String NAME1 = "domain";
  private static final String NAME2 = "domain2";
  public static final String INITIAL_VERSION = "1";
  private final WlsDomainConfigSupport configSupport = new WlsDomainConfigSupport(NAME1);

  private final List<Memento> mementos = new ArrayList<>();
  private RestBackend restBackend;
  private final Domain domain = createDomain(NS, NAME1);
  private final Domain domain2 = createDomain(NS, NAME2);
  private Domain updatedDomain;
  private final DomainConfigurator configurator = DomainConfiguratorFactory.forDomain(domain);
  private final KubernetesTestSupport testSupport = new KubernetesTestSupport();
  private WlsDomainConfig config;

  private static Domain createDomain(String namespace, String name) {
    return new Domain()
        .withMetadata(new V1ObjectMeta().namespace(namespace).name(name))
        .withSpec(new DomainSpec().withDomainUid(name));
  }

  /**
   * Setup test.
   * @throws Exception on failure
   */
  @Before
  public void setUp() throws Exception {
    mementos.add(TestUtils.silenceOperatorLogger());
    mementos.add(testSupport.install());
    mementos.add(
        StaticStubSupport.install(RestBackendImpl.class, "INSTANCE", new TopologyRetrieverStub()));

    testSupport.defineResources(domain, domain2);
    testSupport.doOnCreate(TOKEN_REVIEW, r -> authenticate((V1TokenReview) r));
    testSupport.doOnCreate(SUBJECT_ACCESS_REVIEW, s -> allow((V1SubjectAccessReview) s));
    testSupport.doOnCreate(LOCAL_SUBJECT_ACCESS_REVIEW, s -> allow((V1LocalSubjectAccessReview) s));
    testSupport.doOnUpdate(DOMAIN, d -> updatedDomain = (Domain) d);
    configSupport.addWlsCluster("cluster1", "ms1", "ms2", "ms3", "ms4", "ms5", "ms6");
    restBackend = new RestBackendImpl("", "", Collections.singletonList(NS));

    setupScanCache();
  }

  private void authenticate(V1TokenReview tokenReview) {
    tokenReview.setStatus(new V1TokenReviewStatus().authenticated(true).user(new V1UserInfo()));
  }

  private void allow(V1SubjectAccessReview subjectAccessReview) {
    subjectAccessReview.setStatus(new V1SubjectAccessReviewStatus().allowed(true));
  }

  private void allow(V1LocalSubjectAccessReview localSubjectAccessReview) {
    localSubjectAccessReview.setStatus(new V1SubjectAccessReviewStatus().allowed(true));
  }

  @After
  public void tearDown() {
    mementos.forEach(Memento::revert);
  }

  // functionality needed for Domains resource

  @Test
  public void retrieveRegisteredDomainIds() {
    assertThat(restBackend.getDomainUids(), containsInAnyOrder(NAME1, NAME2));
  }

  // functionality needed for Domain resource

  @Test
  public void validateKnownUid() {
    assertThat(restBackend.isDomainUid(NAME2), is(true));
  }

  @Test
  public void rejectUnknownUid() {
    assertThat(restBackend.isDomainUid("no_such_uid"), is(false));
  }

  @Test(expected = WebApplicationException.class)
  public void whenUnknownDomain_throwException() {
    restBackend.performDomainAction("no_such_uid", new DomainAction(DomainActionType.INTROSPECT));
  }

  @Test(expected = WebApplicationException.class)
  public void whenUnknownDomainUpdateCommand_throwException() {
    restBackend.performDomainAction(NAME1, new DomainAction(null));
  }

  @Test
  public void whenIntrospectionRequestedWhileNoIntrospectVersionDefined_setIntrospectVersion() {
    restBackend.performDomainAction(NAME1, createIntrospectRequest());

    assertThat(getUpdatedIntrospectVersion(), equalTo(INITIAL_VERSION));
  }

  private DomainAction createIntrospectRequest() {
    return new DomainAction(DomainActionType.INTROSPECT);
  }

  private String getUpdatedIntrospectVersion() {
    return getOptionalDomain1().map(Domain::getIntrospectVersion).orElse(null);
  }

  @Nonnull
  private Optional<Domain> getOptionalDomain1() {
    return testSupport.<Domain>getResources(DOMAIN).stream().filter(this::isDomain1).findFirst();
  }

  private boolean isDomain1(Domain domain) {
    return Optional.ofNullable(domain).map(Domain::getMetadata).filter(this::isDomain1Meta).isPresent();
  }

  private boolean isDomain1Meta(V1ObjectMeta meta) {
    return meta != null && NS.equals(meta.getNamespace()) && NAME1.equals(meta.getName());
  }

  @Test
  public void whenIntrospectionRequestedWhileIntrospectVersionNonNumeric_setNumericVersion() {
    configurator.withIntrospectVersion("zork");

    restBackend.performDomainAction(NAME1, createIntrospectRequest());

    assertThat(getUpdatedIntrospectVersion(), equalTo(INITIAL_VERSION));
  }

  @Test
  public void whenIntrospectionRequestedWhileIntrospectVersionDefined_incrementIntrospectVersion() {
    configurator.withIntrospectVersion("17");

    restBackend.performDomainAction(NAME1, createIntrospectRequest());

    assertThat(getUpdatedIntrospectVersion(), equalTo("18"));
  }

  @Test
  public void whenClusterRestartRequestedWhileNoRestartVersionDefined_setRestartVersion() {
    restBackend.performDomainAction(NAME1, createDomainRestartRequest());

    assertThat(getUpdatedRestartVersion(), equalTo(INITIAL_VERSION));
  }

  private DomainAction createDomainRestartRequest() {
    return new DomainAction(DomainActionType.RESTART);
  }

  private String getUpdatedRestartVersion() {
    return getOptionalDomain1().map(Domain::getRestartVersion).orElse(null);
  }

  @Test
  public void whenRestartRequestedWhileRestartVersionDefined_incrementIntrospectVersion() {
    configurator.withRestartVersion("23");

    restBackend.performDomainAction(NAME1, createDomainRestartRequest());

    assertThat(getUpdatedRestartVersion(), equalTo("24"));
  }

  // functionality needed for clusters resource

  @Test
  public void retrieveDefinedClusters() {
    configSupport.addWlsCluster("cluster1", "ms1", "ms2", "ms3");
    configSupport.addWlsCluster("cluster2", "ms4", "ms5", "ms6");
    setupScanCache();

    assertThat(restBackend.getClusters(NAME1), containsInAnyOrder("cluster1", "cluster2"));
  }

  // functionality needed for cluster resource

  @Test
  public void acceptDefinedClusterName() {
    configSupport.addWlsCluster("cluster1", "ms1", "ms2", "ms3");
    configSupport.addWlsCluster("cluster2", "ms4", "ms5", "ms6");
    setupScanCache();

    assertThat(restBackend.isCluster(NAME1, "cluster1"), is(true));
  }

  @Test
  public void rejectUndefinedClusterName() {
    configSupport.addWlsCluster("cluster1", "ms1", "ms2", "ms3");
    configSupport.addWlsCluster("cluster2", "ms4", "ms5", "ms6");
    setupScanCache();

    assertThat(restBackend.isCluster(NAME1, "cluster3"), is(false));
  }

  @Test
  public void whenDomainRestartRequestedWhileNoRestartVersionDefined_setRestartVersion() {
    restBackend.performDomainAction(NAME1, createDomainRestartRequest());

    assertThat(getUpdatedRestartVersion(), equalTo(INITIAL_VERSION));
  }

  // functionality used for scale resource

  @Test(expected = WebApplicationException.class)
  public void whenNegativeScaleSpecified_throwException() {
    restBackend.scaleCluster(NAME1, "cluster1", -1);
  }

  @Test
  public void whenPerClusterReplicaSettingMatchesScaleRequest_doNothing() {
    configureCluster("cluster1").withReplicas(5);

    restBackend.scaleCluster(NAME1, "cluster1", 5);

    assertThat(getUpdatedDomain(), nullValue());
  }

  private Domain getUpdatedDomain() {
    return updatedDomain;
  }

  private ClusterConfigurator configureCluster(String clusterName) {
    return configureDomain().configureCluster(clusterName);
  }

  @Test
  public void whenPerClusterReplicaSetting_scaleClusterUpdatesSetting() {
    configureCluster("cluster1").withReplicas(1);

    restBackend.scaleCluster(NAME1, "cluster1", 5);

    assertThat(getUpdatedDomain().getReplicaCount("cluster1"), equalTo(5));
  }

  @Test
  @Ignore
  public void whenNoPerClusterReplicaSetting_scaleClusterCreatesOne() {
    restBackend.scaleCluster(NAME1, "cluster1", 5);

    assertThat(getUpdatedDomain().getReplicaCount("cluster1"), equalTo(5));
  }

  @Test
  public void whenNoPerClusterReplicaSettingAndDefaultMatchesRequest_doNothing() {
    configureDomain().withDefaultReplicaCount(REPLICA_LIMIT);

    restBackend.scaleCluster(NAME1, "cluster1", REPLICA_LIMIT);

    assertThat(getUpdatedDomain(), nullValue());
  }

  @Test(expected = WebApplicationException.class)
  public void whenReplaceDomainReturnsError_scaleClusterThrowsException() {
    testSupport.failOnResource(DOMAIN, NAME2, NS, HTTP_CONFLICT);

    DomainConfiguratorFactory.forDomain(domain2).configureCluster("cluster1").withReplicas(2);

    restBackend.scaleCluster(NAME2, "cluster1", 3);
  }

  @Test
  public void verify_getWlsDomainConfig_returnsWlsDomainConfig() {
    WlsDomainConfig wlsDomainConfig = ((RestBackendImpl) restBackend).getWlsDomainConfig(NAME1);

    assertThat(wlsDomainConfig.getName(), equalTo(NAME1));
  }

  @Test
  public void verify_getWlsDomainConfig_doesNotReturnNull_whenNoSuchDomainUid() {
    WlsDomainConfig wlsDomainConfig =
        ((RestBackendImpl) restBackend).getWlsDomainConfig("NoSuchDomainUID");

    assertThat(wlsDomainConfig, notNullValue());
  }

  @Test
  public void verify_getWlsDomainConfig_doesNotReturnNull_whenScanIsNull() {
    config = null;

    WlsDomainConfig wlsDomainConfig = ((RestBackendImpl) restBackend).getWlsDomainConfig(NAME1);

    assertThat(wlsDomainConfig, notNullValue());
  }

  @Test
  public void verify_initializeCallBuilder_withAccessToken_userInfoIsNull() {
    RestBackendImpl restBackendImpl = new RestBackendImpl("", "", Collections.singletonList(NS));
    assertThat(restBackendImpl.getUserInfo(), nullValue());
  }

  @Test
  public void verify_initializeCallBuilder_withTokenReview_userInfoNotNull() {
    RestBackEndStub restBackEndStub = new RestBackEndStub("", "", Collections.singletonList(NS));
    assertThat(restBackEndStub.getUserInfo(), notNullValue());
  }

  @Test
  public void verify_authorizationCheck_notCalled_whenAuthenticateWithTokenReviewIsFalse()
      throws NoSuchFieldException, IllegalAccessException {
    RestBackendImpl restBackendImpl = new RestBackendImpl("", "", Collections.singletonList(NS));
    AuthorizationProxyStub authorizationProxyStub = new AuthorizationProxyStub();
    Field nameField = restBackendImpl.getClass()
        .getDeclaredField("atz");
    nameField.setAccessible(true);

    nameField.set(restBackendImpl, authorizationProxyStub);
    restBackendImpl.getClusters(NAME1);
    assertThat(authorizationProxyStub.atzCheck, is(false));
  }

  @Test
  public void verify_authorizationCheck_isCalled_whenAuthenticateWithTokenReviewIsTrue()
      throws NoSuchFieldException, IllegalAccessException {
    RestBackEndStub restBackEndStub = new RestBackEndStub("", "", Collections.singletonList(NS));
    AuthorizationProxyStub authorizationProxyStub = new AuthorizationProxyStub();
    Field nameField = restBackEndStub.getClass()
        .getSuperclass()
        .getDeclaredField("atz");
    nameField.setAccessible(true);

    nameField.set(restBackEndStub, authorizationProxyStub);
    restBackEndStub.getClusters(NAME1);
    assertThat(authorizationProxyStub.atzCheck, is(true));
  }

  @Test
  public void verify_apiClient_configured_with_AccessTokenAuthentication()
      throws IllegalAccessException {
    RestBackendImpl restBackendImpl = new RestBackendImpl("", "1234", Collections.singletonList(NS));
    CallBuilder callBuilder = getValue(restBackendImpl, "callBuilder");
    ClientPool pool = getValue(callBuilder, "helper");
    ApiClient apiClient = pool.take();
    Authentication authentication = apiClient.getAuthentication("BearerToken");
    assertThat(authentication instanceof ApiKeyAuth, is(true));
    String apiKey = ((ApiKeyAuth) authentication).getApiKey();
    assertThat(apiKey, is("1234"));
  }

  @Test
  public void verify_apiClient_configured_whenAuthenticateWithTokenReviewIsTrue()
      throws IllegalAccessException {
    RestBackEndStub restBackEndStub = new RestBackEndStub("", "", Collections.singletonList(NS));
    CallBuilder callBuilder = getValue(restBackEndStub, "callBuilder");
    ClientPool pool = getValue(callBuilder, "helper");
    ApiClient apiClient = pool.take();
    Authentication authentication = apiClient.getAuthentication("BearerToken");
    assertThat(authentication instanceof ApiKeyAuth, is(true));
    String apiKey = ((ApiKeyAuth) authentication).getApiKey();
    assertNull(apiKey);
  }


  private DomainConfigurator configureDomain() {
    return configurator;
  }

  private void setupScanCache() {
    config = configSupport.createDomainConfig();
  }

  @SuppressWarnings("unchecked")
  public static <T> T getValue(Object object, String fieldName) throws IllegalAccessException {
    return (T) getValue(object, getField(object.getClass(), fieldName));
  }

  private static Object getValue(Object object, Field field) throws IllegalAccessException {
    boolean wasAccessible = field.isAccessible();
    try {
      field.setAccessible(true);
      return field.get(object);
    } finally {
      field.setAccessible(wasAccessible);
    }
  }

  private static Field getField(Class<?> aaClass, String fieldName) {
    assert aaClass != null : "No such field '" + fieldName + "'";

    try {
      return aaClass.getDeclaredField(fieldName);
    } catch (NoSuchFieldException e) {
      return getField(aaClass.getSuperclass(), fieldName);
    }
  }

  private class TopologyRetrieverStub implements TopologyRetriever {
    @Override
    public WlsDomainConfig getWlsDomainConfig(String ns, String domainUid) {
      return config;
    }
  }

  private class RestBackEndStub extends RestBackendImpl {

    /**
     * Construct a RestBackendImpl that is used to handle one WebLogic operator REST request.
     *
     * @param principal        is the name of the Kubernetes user to use when calling the Kubernetes
     *                         REST api.
     * @param accessToken      is the access token of the Kubernetes service account of the client
     *                         calling the WebLogic operator REST api.
     * @param domainNamespaces a list of Kubernetes namepaces that contain domains that the WebLogic
     */
    RestBackEndStub(String principal, String accessToken,
        Collection<String> domainNamespaces) {
      super(principal, accessToken, domainNamespaces);
    }

    protected boolean authenticateWithTokenReview() {
      return true;
    }
  }

  private class AuthorizationProxyStub extends AuthorizationProxy {
    boolean atzCheck = false;

    /**
     * Check if the specified principal is allowed to perform the specified operation on the specified
     * resource in the specified scope.
     *
     * @param principal The user, group or service account.
     * @param groups The groups that principal is a member of.
     * @param operation The operation to be authorized.
     * @param resource The kind of resource on which the operation is to be authorized.
     * @param resourceName The name of the resource instance on which the operation is to be
     *     authorized.
     * @param scope The scope of the operation (cluster or namespace).
     * @param namespaceName name of the namespace if scope is namespace else null.
     * @return true if the operation is allowed, or false if not.
     */
    public boolean check(
        String principal,
        final List<String> groups,
        Operation operation,
        Resource resource,
        String resourceName,
        Scope scope,
        String namespaceName) {
      atzCheck = true;
      return atzCheck;
    }
  }
}
