package team.projectpulse.security;

import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import team.projectpulse.user.PeerEvaluationUser;
import team.projectpulse.user.UserRepository;

/** FR-SEC-active-account: signed credentials do not override current account status. */
@Component
public class ActiveAccountJwtValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2TokenValidatorResult DENIED = OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "The current account cannot be authenticated.", null));
    private final UserRepository users;

    public ActiveAccountJwtValidator(UserRepository users) {
        this.users = users;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Object claim = token.getClaim("userId");
        if (claim == null || !claim.toString().matches("[1-9][0-9]*")) return DENIED;
        try {
            int id = Integer.parseInt(claim.toString());
            return users.findById(id).filter(PeerEvaluationUser::isEnabled).isPresent()
                    ? OAuth2TokenValidatorResult.success() : DENIED;
        } catch (NumberFormatException | DataAccessException ex) {
            // Fail closed without returning repository/connection details to the client.
            return DENIED;
        }
    }
}
