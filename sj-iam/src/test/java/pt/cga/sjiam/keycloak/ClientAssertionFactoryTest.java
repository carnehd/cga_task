package pt.cga.sjiam.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.Test;

import pt.cga.sjiam.TestFixtures;
import pt.cga.sjiam.keys.SigningKeyProvider;

//USAR CODIGO: migrar com a classe testada.

/** Tarefa 3: a client assertion segue a RFC 7523 e é verificável com a chave do JWKS. */
class ClientAssertionFactoryTest {

    private final SigningKeyProvider keys = TestFixtures.signingKeyProvider();
    private final ClientAssertionFactory factory =
            new ClientAssertionFactory(keys, TestFixtures.properties(), TestFixtures.fixedClock());

    @Test
    void buildsAnRs256AssertionWithTheJwksKid() throws Exception {
        SignedJWT jwt = SignedJWT.parse(factory.create());

        assertEquals(JWSAlgorithm.RS256, jwt.getHeader().getAlgorithm());
        assertEquals(JOSEObjectType.JWT, jwt.getHeader().getType());
        assertEquals(TestFixtures.KID, jwt.getHeader().getKeyID());
        assertTrue(jwt.verify(new RSASSAVerifier(keys.publicKey())), "assinatura verificável com a chave pública do JWKS");
    }

    @Test
    void claimsFollowRfc7523() throws Exception {
        JWTClaimsSet claims = SignedJWT.parse(factory.create()).getJWTClaimsSet();

        assertEquals(TestFixtures.CLIENT_ID, claims.getIssuer());
        assertEquals(TestFixtures.CLIENT_ID, claims.getSubject());
        assertEquals(List.of(TestFixtures.TOKEN_ENDPOINT), claims.getAudience());
        assertNotNull(claims.getJWTID());
        assertEquals(Date.from(TestFixtures.NOW), claims.getIssueTime());
        assertEquals(Date.from(TestFixtures.NOW.plusSeconds(60)), claims.getExpirationTime());
    }

    @Test
    void everyAssertionHasAFreshJti() throws Exception {
        String first = SignedJWT.parse(factory.create()).getJWTClaimsSet().getJWTID();
        String second = SignedJWT.parse(factory.create()).getJWTClaimsSet().getJWTID();
        assertNotEquals(first, second);
    }
}
