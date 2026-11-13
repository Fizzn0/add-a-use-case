package team.projectpulse.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.student.Student;
import team.projectpulse.student.StudentRepository;
import team.projectpulse.system.exception.PeerEvaluationIllegalArgumentException;
import team.projectpulse.team.Team;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-inactive-student-evaluations: T11/T12, independent specification oracles. */
@ExtendWith(MockitoExtension.class)
class InactiveStudentEvaluationTest {
    @Mock PeerEvaluationRepository evaluations;
    @Mock StudentRepository students;
    @Mock SectionRepository sections;
    EvaluationService service;
    Student active, inactive;
    Section section;
    @BeforeEach void setup() {
        service = new EvaluationService(evaluations, students, sections,
                Clock.fixed(Instant.parse("2023-08-13T12:00:00Z"), ZoneOffset.UTC));
        section = new Section(); section.setSectionId(1); section.setSectionName("Course section");
        section.setActiveWeeks(List.of("2023-W31"));
        Team team = new Team(); team.setTeamId(1);
        active = new Student("active", "Active", "Student", "a@test.edu", "hash", true, "student");
        active.setId(1); active.setSection(section); active.setTeam(team);
        inactive = new Student("inactive", "Inactive", "Student", "i@test.edu", "hash", false, "student");
        inactive.setId(2); inactive.setSection(section); inactive.setTeam(team);
    }
    @Test void rejectsNewEvaluationOfInactiveTeammate() {
        var evaluation = new PeerEvaluation("2023-W31", active, inactive, List.of(), "public", "private");
        assertThatThrownBy(() -> service.addPeerEvaluation(evaluation))
                .isInstanceOf(PeerEvaluationIllegalArgumentException.class).hasMessageContaining("inactive");
        verify(evaluations, never()).save(any());
    }
    @Test void acceptsActiveTarget() {
        var evaluation = new PeerEvaluation("2023-W31", active, active, List.of(), "public", "private");
        when(evaluations.save(evaluation)).thenReturn(evaluation);
        assertThat(service.addPeerEvaluation(evaluation)).isSameAs(evaluation);
    }
    @Test void inactiveHistoryIsIncludedOnlyInItsRecordedWeekEvenWithoutTeam() {
        inactive.setTeam(null);
        when(sections.findById(1)).thenReturn(Optional.of(section));
        when(students.findBySectionSectionId(1)).thenReturn(List.of(active, inactive));
        when(evaluations.findEvaluatedStudentIds(1,"2023-W31")).thenReturn(List.of(2));
        when(evaluations.findEvaluatedStudentIds(1,"2023-W30")).thenReturn(List.of());
        var saved = new PeerEvaluation("2023-W31", active, inactive, List.of(), "retained", "private");
        when(evaluations.findByWeekAndEvaluateeId(anyString(), anyInt())).thenAnswer(call ->
                call.getArgument(0).equals("2023-W31") && call.getArgument(1).equals(2) ? List.of(saved) : List.of());
        var history = service.generateWeeklyPeerEvaluationReportForSection(1,"2023-W31").getPeerEvaluationAverages();
        assertThat(history).extracting(PeerEvaluationAverage::getStudentId).containsExactly(1,2);
        assertThat(history.get(1).getPublicComments()).containsExactly("retained");
        assertThat(history.get(1).getTeamName()).isNull();
        assertThat(service.generateWeeklyPeerEvaluationReportForSection(1,"2023-W30").getPeerEvaluationAverages())
                .extracting(PeerEvaluationAverage::getStudentId).containsExactly(1);
    }
    @Test void savedEvaluationOfInactiveStudentStillUsesExistingEditWindow() {
        var saved = new PeerEvaluation("2023-W31", active, inactive, List.of(), "old", "private");
        when(evaluations.findById(9)).thenReturn(Optional.of(saved));
        when(evaluations.save(saved)).thenReturn(saved);
        var update = new PeerEvaluation("2023-W31", active, inactive, List.of(), "edited", "private");
        assertThat(service.updatePeerEvaluation(9,update).getPublicComment()).isEqualTo("edited");
        assertThat(saved.getEvaluatee()).isSameAs(inactive);
    }
}
