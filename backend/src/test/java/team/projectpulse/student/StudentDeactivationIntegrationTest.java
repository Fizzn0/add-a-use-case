package team.projectpulse.student;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.activity.ActivityRepository;
import team.projectpulse.evaluation.PeerEvaluationRepository;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.user.UserRepository;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MySQL + actual filter chain coverage. Requires the project's Docker test containers. */
class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {
    @Autowired jakarta.persistence.EntityManagerFactory entityManagers;
    @Autowired MockMvc mvc;
    @Autowired StudentRepository students;
    @Autowired UserRepository users;
    @Autowired ActivityRepository activities;
    @Autowired PeerEvaluationRepository evaluations;
    @Autowired SectionRepository sections;

    private Student john() { return (Student) users.findByEmail("j.smith@abc.edu").orElseThrow(); }
    private String login(String username) throws Exception {
        String json = mvc.perform(post("/api/v1/users/login").with(httpBasic(username,"123456")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + new JSONObject(json).getJSONObject("data").getString("token");
    }
    private String action(int id) { return "/api/v1/students/"+id+"/deactivate"; }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void overlappingProfileAndLifecycleWritesPreserveEachOthersFields() {
        int id=john().getId(); String originalName=john().getFirstName();
        try (var profile=entityManagers.createEntityManager(); var lifecycle=entityManagers.createEntityManager()) {
            profile.getTransaction().begin();
            Student staleProfile=profile.find(Student.class,id);
            lifecycle.getTransaction().begin();
            lifecycle.find(Student.class,id).setEnabled(false);
            lifecycle.getTransaction().commit();
            staleProfile.setFirstName("Concurrent profile");
            profile.getTransaction().commit();
            profile.clear(); lifecycle.clear();
            assertThat(profile.find(Student.class,id).isEnabled()).isFalse();

            // Reverse the commit order: lifecycle must not overwrite a newer profile.
            lifecycle.getTransaction().begin();
            Student staleLifecycle=lifecycle.find(Student.class,id);
            profile.getTransaction().begin();
            profile.find(Student.class,id).setFirstName("Newer profile");
            profile.getTransaction().commit();
            staleLifecycle.setEnabled(true);
            lifecycle.getTransaction().commit();
            lifecycle.clear();
            assertThat(lifecycle.find(Student.class,id).getFirstName()).isEqualTo("Newer profile");
        } finally {
            Student restore=students.findById(id).orElseThrow();
            restore.setEnabled(true); restore.setFirstName(originalName); students.saveAndFlush(restore);
        }
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void persistenceFailureRollsBackAlreadyFlushedDeactivation() {
        int id=john().getId();
        try (var em=entityManagers.createEntityManager()) {
            em.getTransaction().begin();
            try {
                Student target=em.find(Student.class,id);
                target.setEnabled(false); em.flush();
                target.setEmail(null); // required field forces persistence validation failure
                assertThatThrownBy(em::flush).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
            } finally {
                em.getTransaction().rollback();
            }
        }
        assertThat(students.findById(id).orElseThrow().isEnabled()).isTrue();
    }

    @Test void confirmedAndRepeatedActionPreservesDataAndMembership() throws Exception {
        Student student = john(); int id=student.getId();
        var team=student.getTeam(); var section=student.getSection();
        long activityCount=activities.count(), evaluationCount=evaluations.count(), accountCount=users.count();
        String token=login(section.getCourse().getCourseAdmin().getUsername());
        mvc.perform(post(action(id)).header(HttpHeaders.AUTHORIZATION,token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(post(action(id)).header(HttpHeaders.AUTHORIZATION,token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Student is already deactivated"));
        students.flush();
        assertThat(students.findById(id).orElseThrow().getTeam()).isEqualTo(team);
        assertThat(student.getSection()).isEqualTo(section);
        assertThat(activities.count()).isEqualTo(activityCount);
        assertThat(evaluations.count()).isEqualTo(evaluationCount);
        assertThat(users.count()).isEqualTo(accountCount);
    }
    @Test void ownerNotAssignedToSectionCanReadAndDeactivate() throws Exception {
        Student student=john(); var section=student.getSection(); var owner=section.getCourse().getCourseAdmin();
        section.getInstructors().removeIf(i -> i.getId().equals(owner.getId()));
        sections.saveAndFlush(section);
        String token=login(owner.getUsername());
        mvc.perform(get("/api/v1/students/"+student.getId()).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk());
        mvc.perform(post(action(student.getId())).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk());
    }
    @Test void unauthorizedAndMissingTargetsAreRefused() throws Exception {
        int id=john().getId();
        String studentToken=login(john().getUsername());
        String unrelatedAdmin=login("t.cook@abc.edu");
        mvc.perform(post(action(id))).andExpect(status().isUnauthorized());
        mvc.perform(post(action(id)).header(HttpHeaders.AUTHORIZATION,studentToken)).andExpect(status().isForbidden());
        mvc.perform(post(action(id)).header(HttpHeaders.AUTHORIZATION,unrelatedAdmin)).andExpect(status().isForbidden());
        mvc.perform(post(action(Integer.MAX_VALUE)).header(HttpHeaders.AUTHORIZATION,unrelatedAdmin)).andExpect(status().isForbidden());
        assertThat(john().isEnabled()).isTrue();
    }
    @Test void selfProfileCannotActivateOrDeactivate() throws Exception {
        Student student=john(); String token=login(student.getUsername());
        String body=new JSONObject().put("username",student.getUsername()).put("firstName","Updated")
                .put("lastName",student.getLastName()).put("email",student.getEmail()).put("enabled",false).toString();
        mvc.perform(put("/api/v1/students/"+student.getId()).header(HttpHeaders.AUTHORIZATION,token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(put("/api/v1/students/"+student.getId()).header(HttpHeaders.AUTHORIZATION,token)
                        .contentType(MediaType.APPLICATION_JSON).content(new JSONObject(body).put("enabled",true).toString()))
                .andExpect(status().isForbidden());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void committedDeactivationRejectsTheSameTokenAndNewLogin() throws Exception {
        Student student=john(); int id=student.getId();
        String oldToken=login(student.getUsername());
        String ownerToken=login(student.getSection().getCourse().getCourseAdmin().getUsername());
        try {
            mvc.perform(post(action(id)).header(HttpHeaders.AUTHORIZATION,ownerToken)).andExpect(status().isOk());
            mvc.perform(get("/api/v1/students/"+id).header(HttpHeaders.AUTHORIZATION,oldToken)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/users/login").with(httpBasic(student.getUsername(),"123456"))).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/students/"+id).header(HttpHeaders.AUTHORIZATION,ownerToken)).andExpect(status().isOk());
        } finally {
            Student restore=students.findById(id).orElseThrow(); restore.setEnabled(true); students.saveAndFlush(restore);
        }
    }
}
