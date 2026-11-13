package team.projectpulse.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import team.projectpulse.system.UserUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentLifecycleAuthorizationTest {
    @Mock StudentRepository students;
    @Mock UserUtils user;
    @InjectMocks StudentSecurityService security;

    @Test void rejectsStudentSelfAndAnonymousWithoutQuery() {
        assertThat(security.canManageLifecycle(7)).isFalse();
        when(user.getUserId()).thenReturn(7);
        assertThat(security.canManageLifecycle(7)).isFalse();
        verifyNoInteractions(students);
    }
    @Test void adminRoleRequiresActualCourseScope() {
        when(user.getUserId()).thenReturn(2);
        when(user.hasRole(anyString())).thenAnswer(call -> call.getArgument(0).equals("ROLE_admin"));
        when(students.findInLifecycleScope(7,2)).thenReturn(Optional.empty());
        assertThat(security.canManageLifecycle(7)).isFalse();
        when(students.findInLifecycleScope(7,2)).thenReturn(Optional.of(new Student()));
        assertThat(security.canManageLifecycle(7)).isTrue();
    }
    @Test void assignedInstructorRequiresScopedTarget() {
        when(user.getUserId()).thenReturn(2);
        when(user.hasRole("ROLE_instructor")).thenReturn(true);
        when(students.findInLifecycleScope(7,2)).thenReturn(Optional.of(new Student()));
        assertThat(security.canManageLifecycle(7)).isTrue();
        assertThat(security.canManageLifecycle(99)).isFalse();
        assertThat(security.canManageLifecycle(null)).isFalse();
    }

    @Test void unavailableScopeLookupDeniesAccess() {
        when(user.getUserId()).thenReturn(2);
        when(user.hasRole("ROLE_instructor")).thenReturn(true);
        when(students.findInLifecycleScope(7,2)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database detail"));
        assertThat(security.canManageLifecycle(7)).isFalse();
    }
}
