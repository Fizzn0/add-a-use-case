package team.projectpulse.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.*;
import team.projectpulse.student.Student;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tests the production decoder with real RSA signatures, not SecurityMockMvcRequestPostProcessors.jwt(). */
@ExtendWith(MockitoExtension.class)
class ActiveAccountSignedTokenTest {
    @Mock UserRepository users;
    @InjectMocks SecurityConfiguration security;

    private String signedToken(Instant expiry) {
        return security.jwtEncoder().encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .subject("student").issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiry)
                .claim("userId",7).claim("authorities","ROLE_student").build())).getTokenValue();
    }
    @Test void sameSignedTokenFailsAfterAccountIsDisabled() {
        Student student = new Student(); student.setEnabled(true);
        when(users.findById(7)).thenReturn(Optional.of(student));
        JwtDecoder decoder = security.jwtDecoder(new ActiveAccountJwtValidator(users));
        String token = signedToken(Instant.now().plusSeconds(3600));
        assertThat(decoder.decode(token).getSubject()).isEqualTo("student");
        student.setEnabled(false);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }
    @Test void defaultExpiryValidationIsPreserved() {
        Student student = new Student(); student.setEnabled(true);
        when(users.findById(7)).thenReturn(Optional.of(student));
        JwtDecoder decoder = security.jwtDecoder(new ActiveAccountJwtValidator(users));
        assertThatThrownBy(() -> decoder.decode(signedToken(Instant.now().minusSeconds(120))))
                .isInstanceOf(JwtValidationException.class).hasMessageContaining("expired");
    }
    @Test void badSignatureIsRejectedBeforeAccountLookup() {
        String token = signedToken(Instant.now().plusSeconds(3600));
        int signatureStart = token.lastIndexOf('.') + 1;
        String bad = token.substring(0,signatureStart) + (token.charAt(signatureStart)=='A'?'B':'A') + token.substring(signatureStart+1);
        assertThatThrownBy(() -> security.jwtDecoder(new ActiveAccountJwtValidator(users)).decode(bad))
                .isInstanceOf(JwtException.class);
        verifyNoInteractions(users);
    }
    @Test void disabledInstructorAndDeletedUserAreAlsoDenied() {
        Instructor instructor = new Instructor(); instructor.setEnabled(false);
        when(users.findById(7)).thenReturn(Optional.of(instructor),Optional.empty());
        JwtDecoder decoder = security.jwtDecoder(new ActiveAccountJwtValidator(users));
        String token = signedToken(Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }
}
