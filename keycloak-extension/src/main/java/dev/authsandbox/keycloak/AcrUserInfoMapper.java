package dev.authsandbox.keycloak;

import org.keycloak.authentication.authenticators.util.LoAUtil;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.protocol.oidc.utils.AcrUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;
import org.keycloak.services.managers.AuthenticationManager;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Protocol mapper that includes the {@code acr} claim in the userinfo response.
 *
 * <p>Keycloak's built-in {@code oidc-acr-mapper} ({@code AcrProtocolMapper}) does not implement
 * {@code UserInfoTokenMapper} in Keycloak 26, so it never adds the claim to userinfo.
 *
 * <p>This mapper replicates the LoA-resolution logic of {@code AcrProtocolMapper} (reading
 * {@code Constants.LEVEL_OF_AUTHENTICATION} from the authenticated client session, then mapping
 * the integer LoA to an ACR string via the realm/client {@code acr.loa.map}) and writes the
 * result to the userinfo token via {@code token.getOtherClaims()}.
 *
 * <p>Configure this mapper on each client with {@code userinfo.token.claim=true}.
 */
public class AcrUserInfoMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    public static final String PROVIDER_ID = "acr-userinfo-mapper";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "ACR (userinfo)";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Adds the acr claim to the userinfo response. Replicates AcrProtocolMapper logic "
                + "(LoAUtil + AcrUtils) since oidc-acr-mapper does not implement UserInfoTokenMapper.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return Collections.emptyList();
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel,
                            UserSessionModel userSession, KeycloakSession keycloakSession,
                            ClientSessionContext clientSessionCtx) {
        AuthenticatedClientSessionModel clientSession = clientSessionCtx.getClientSession();

        int loa = LoAUtil.getCurrentLevelOfAuthentication(clientSession);
        if (loa < Constants.MINIMUM_LOA) {
            loa = AuthenticationManager.isSSOAuthentication(clientSession) ? 0 : 1;
        }

        Map<String, Integer> acrLoaMap = AcrUtils.getAcrLoaMap(clientSession.getClient());

        String acr = AcrUtils.mapLoaToAcr(loa, acrLoaMap, AcrUtils.getRequiredAcrValues(
                clientSession.getNote(OIDCLoginProtocol.CLAIMS_PARAM)));
        if (acr == null) {
            acr = AcrUtils.mapLoaToAcr(loa, acrLoaMap, AcrUtils.getAcrValues(
                    clientSession.getNote(OIDCLoginProtocol.CLAIMS_PARAM),
                    clientSession.getNote(OIDCLoginProtocol.ACR_PARAM),
                    clientSession.getClient()));
            if (acr == null) {
                acr = AcrUtils.mapLoaToAcr(loa, acrLoaMap, acrLoaMap.keySet());
                if (acr == null) {
                    acr = String.valueOf(loa);
                }
            }
        }

        token.getOtherClaims().put("acr", acr);
    }
}
