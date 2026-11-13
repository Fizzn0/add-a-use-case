package team.projectpulse.student.dto;

import jakarta.validation.constraints.NotEmpty;

/** Profile updates cannot deactivate; true preserves the explicit legacy activation path. */
public record StudentUpdateRequest(
        @NotEmpty(message = "username is required.") String username,
        @NotEmpty(message = "first name is required.") String firstName,
        @NotEmpty(message = "last name is required.") String lastName,
        @NotEmpty(message = "email is required.") String email,
        Boolean enabled) {
}
