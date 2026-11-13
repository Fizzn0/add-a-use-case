package team.projectpulse.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.student.dto.StudentUpdateRequest;
import team.projectpulse.system.UserUtils;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.team.Team;
import team.projectpulse.user.userinvitation.UserInvitationService;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student T02/T03/T04/T06/T10. Persistence rollback is an integration concern. */
@ExtendWith(MockitoExtension.class)
class StudentDeactivationServiceTest {
    @Mock StudentRepository students;
    @Mock SectionRepository sections;
    @Mock UserInvitationService invitations;
    @Mock PasswordEncoder passwords;
    @Mock UserUtils user;
    @InjectMocks StudentService service;

    private Student authorizedStudent() {
        Student student = new Student("student", "First", "Last", "s@example.test", "hash", true, "student");
        student.setId(7);
        when(user.getUserId()).thenReturn(2);
        when(user.hasRole("ROLE_instructor")).thenReturn(true);
        when(students.findInLifecycleScope(7, 2)).thenReturn(Optional.of(student));
        return student;
    }

    @Test void deactivationPreservesMembershipAndProfile() {
        Student student = authorizedStudent();
        Team team = new Team(); Section section = new Section();
        student.setTeam(team); student.setSection(section);
        Student before = new Student(student.getUsername(), student.getFirstName(), student.getLastName(),
                student.getEmail(), student.getPassword(), false, student.getRoles());
        before.setId(7); before.setTeam(team); before.setSection(section);
        var result = service.deactivateStudent(7);
        assertThat(result.alreadyInactive()).isFalse();
        assertThat(result.student()).usingRecursiveComparison().isEqualTo(before);
        verify(students).save(student);
        verifyNoMoreInteractions(students);
    }

    @Test void repeatedDeactivationAndNoTeamAreHarmless() {
        Student student = authorizedStudent();
        service.deactivateStudent(7);
        assertThat(service.deactivateStudent(7).alreadyInactive()).isTrue();
        assertThat(student.getTeam()).isNull();
        verify(students, times(1)).save(student);
    }

    @Test void studentSelfCannotDeactivateOrExplicitlyActivate() {
        when(user.getUserId()).thenReturn(7);
        assertThatThrownBy(() -> service.deactivateStudent(7)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.updateStudent(7, update(true))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(students);
    }

    @Test void unrelatedInstructorCannotMutateThroughService() {
        when(user.getUserId()).thenReturn(2);
        when(user.hasRole("ROLE_instructor")).thenReturn(true);
        when(students.findInLifecycleScope(7, 2)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivateStudent(7)).isInstanceOf(ObjectNotFoundException.class);
        assertThatThrownBy(() -> service.updateStudent(7, update(true))).isInstanceOf(AccessDeniedException.class);
        verify(students, never()).save(any());
    }

    @Test void profileUpdateCannotDisableAndStaleSaveCannotReactivate() {
        Student student = authorizedStudent();
        service.updateStudent(7, update(false));
        assertThat(student.isEnabled()).isTrue();
        student.setEnabled(false);
        service.updateStudent(7, update(null));
        assertThat(student.isEnabled()).isFalse();
        service.updateStudent(7, update(true));
        assertThat(student.isEnabled()).isTrue();
    }

    private StudentUpdateRequest update(Boolean enabled) {
        return new StudentUpdateRequest("student", "Changed", "Last", "s@example.test", enabled);
    }
}
