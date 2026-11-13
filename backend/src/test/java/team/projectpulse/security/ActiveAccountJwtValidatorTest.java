package team.projectpulse.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.student.Student;
import team.projectpulse.user.UserRepository;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** FR-SEC-active-account / T08-T09: the current database state wins over the signed claim. */
@ExtendWith(MockitoExtension.class)
class ActiveAccountJwtValidatorTest {
    @Mock UserRepository users;
    private Jwt token(Object id) {
        return Jwt.withTokenValue("test").header("alg", "RS256").claim("userId", id).build();
    }
    @Test void rechecksCurrentAccountOnEveryValidation() {
        Student student = new Student(); student.setEnabled(true);
        when(users.findById(7)).thenReturn(Optional.of(student));
        var validator = new ActiveAccountJwtValidator(users);
        Jwt jwt = token(7L);
        assertThat(validator.validate(jwt).hasErrors()).isFalse();
        student.setEnabled(false);
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
        verify(users, times(2)).findById(7);
    }
    @ParameterizedTest @ValueSource(strings={"0", "-1", "1.0", "2147483648", "NaN", "abc"})
    void rejectsMalformedIdentity(String id) {
        assertThat(new ActiveAccountJwtValidator(users).validate(token(id)).hasErrors()).isTrue();
        verifyNoInteractions(users);
    }
    @Test void rejectsMissingIdentityAndDeletedAccount() {
        var validator = new ActiveAccountJwtValidator(users);
        assertThat(validator.validate(Jwt.withTokenValue("test").header("alg", "RS256").subject("user").build()).hasErrors()).isTrue();
        when(users.findById(7)).thenReturn(Optional.empty());
        assertThat(validator.validate(token(7)).hasErrors()).isTrue();
    }
    @Test void databaseFailureDeniesWithoutExposingDetails() {
        when(users.findById(7)).thenThrow(new DataAccessResourceFailureException("private database hostname"));
        var result = new ActiveAccountJwtValidator(users).validate(token(7));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).allSatisfy(error -> assertThat(error.getDescription()).doesNotContain("hostname"));
    }
}
