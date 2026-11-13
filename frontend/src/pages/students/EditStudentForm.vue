<template>
  <el-form
    ref="studentForm"
    :model="studentBeingEdited"
    :rules="rules"
    label-width="110px"
    style="padding-right: 30px"
    label-position="left"
  >
    <el-form-item label="Id:">
      <el-input v-model="studentBeingEdited.id" disabled></el-input>
    </el-form-item>
    <el-form-item label="Username:" prop="username">
      <el-input v-model="studentBeingEdited.username"></el-input>
    </el-form-item>
    <el-form-item label="Status:">
      <el-tag v-if="loadedEnabled" type="success">Enabled</el-tag>
      <el-switch
        v-else
        v-model="requestActivation"
        inline-prompt
        active-text="Enabled"
        inactive-text="Disabled"
        style="--el-switch-on-color: #13ce66; --el-switch-off-color: #ff4949"
      />
      <el-button
        v-if="loadedEnabled"
        type="danger"
        plain
        data-cy="deactivate-open"
        :disabled="deactivating || !loaded"
        :loading="deactivating"
        @click="confirmDeactivation"
        >Deactivate student</el-button
      >
    </el-form-item>
    <el-alert v-if="deactivationFailed" type="error" :closable="false" data-cy="deactivate-error">
      Deactivation could not be confirmed. Retry the action or refresh the student's status.
      <el-button data-cy="deactivate-refresh" @click="refreshStatus">Refresh status</el-button>
    </el-alert>
    <el-form-item label="First Name:" prop="firstName">
      <el-input v-model="studentBeingEdited.firstName"></el-input>
    </el-form-item>
    <el-form-item label="Last Name:" prop="lastName">
      <el-input v-model="studentBeingEdited.lastName"></el-input>
    </el-form-item>
    <el-form-item label="Email:" prop="email">
      <el-input v-model="studentBeingEdited.email"></el-input>
    </el-form-item>
    <el-form-item label="Team:" prop="teamName">
      <el-input v-model="studentBeingEdited.teamName" disabled></el-input>
    </el-form-item>
    <div style="display: flex; justify-content: end">
      <el-button @click="$emit('close-dialog')"> Cancel </el-button>
      <el-button
        type="primary"
        :disabled="deactivating || !loaded"
        @click="updateExistingStudent()"
      >
        Confirm
      </el-button>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Student } from '@/apis/student/types'
import type { FormInstance } from 'element-plus'
import { findStudentById, updateStudent, deactivateStudent } from '@/apis/student'
import { ref, onMounted } from 'vue'

const { studentId } = defineProps(['studentId'])
const emit = defineEmits(['close-dialog'])
const loaded = ref(false)
const loadedEnabled = ref(false)
const requestActivation = ref(false)
const deactivating = ref(false)
const deactivationFailed = ref(false)
const selectedStudentName = ref('')

// Note that the structure of the userData is NOT the same as the User interface since the roles are stored as an array
const studentBeingEdited = ref<Student>({
  // Data of the user being added or edited
  id: NaN,
  username: '',
  enabled: false,
  firstName: '',
  lastName: '',
  email: ''
})

const studentForm = ref<FormInstance>() // Form reference

// Validation rules
const rules = {
  username: [
    { required: true, message: 'Please input your email as your username', trigger: 'blur' },
    {
      pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
      message: 'Enter your email as username.',
      trigger: 'blur'
    }
  ],
  firstName: [
    { required: true, message: 'Please provide the first name of the user.', trigger: 'blur' }
  ],
  lastName: [
    { required: true, message: 'Please provide the last name of the user.', trigger: 'blur' }
  ],
  email: [
    { required: true, message: 'Please provide the email of the user.', trigger: 'blur' },
    { type: 'email', message: 'Please provide a valid email.', trigger: 'blur' }
  ]
}

async function loadStudent(studentId: number) {
  // Load the user to be edited
  const result = await findStudentById(studentId)

  studentBeingEdited.value.id = result.data.id as number
  studentBeingEdited.value.username = result.data.username
  studentBeingEdited.value.enabled = result.data.enabled
  studentBeingEdited.value.firstName = result.data.firstName
  studentBeingEdited.value.lastName = result.data.lastName
  studentBeingEdited.value.email = result.data.email
  studentBeingEdited.value.teamName = result.data.teamName
  selectedStudentName.value = `${result.data.firstName} ${result.data.lastName}`
  loadedEnabled.value = result.data.enabled === true
  requestActivation.value = false
  loaded.value = true
}

async function refreshStatus() {
  try {
    const result = await findStudentById(studentId)
    loadedEnabled.value = result.data.enabled === true
    studentBeingEdited.value.enabled = result.data.enabled
    requestActivation.value = false
    deactivationFailed.value = false
  } catch {
    deactivationFailed.value = true
  }
}

async function confirmDeactivation() {
  if (deactivating.value || !loadedEnabled.value) return
  deactivating.value = true
  try {
    try {
      await ElMessageBox.confirm(
        `Deactivate ${selectedStudentName.value}? This student will lose access, including an existing login, and stop receiving submission reminders. Teammates cannot submit new evaluations of this student. Team membership, the account, and all submitted work are retained, not deleted. Weekly evaluation reports include this student only for weeks with saved evaluations of them. Unsaved profile edits are not submitted.`,
        'Confirm student deactivation',
        {
          type: 'warning',
          confirmButtonText: 'Deactivate student',
          cancelButtonText: 'Cancel',
          distinguishCancelAndClose: true
        }
      )
    } catch {
      return // Cancellation, Escape and close never send a request.
    }
    deactivationFailed.value = false
    try {
      const result = await deactivateStudent(studentId)
      loadedEnabled.value = result.data.enabled === true
      studentBeingEdited.value.enabled = result.data.enabled
      requestActivation.value = false
      ElMessage.success(result.message)
      emit('close-dialog')
    } catch {
      deactivationFailed.value = true
    }
  } finally {
    deactivating.value = false
  }
}

onMounted(() => {
  loadStudent(studentId) // Load the artifact to be edited
})

async function updateExistingStudent() {
  await studentForm.value!.validate() // Validate the form

  const updatedStudent: Student = {
    id: studentBeingEdited.value.id,
    username: studentBeingEdited.value.username,
    ...(!loadedEnabled.value && requestActivation.value ? { enabled: true } : {}),
    firstName: studentBeingEdited.value.firstName,
    lastName: studentBeingEdited.value.lastName,
    email: studentBeingEdited.value.email
  }
  // Call the API to update the user
  await updateStudent(updatedStudent)

  ElMessage.success('Student updated successfully')
  emit('close-dialog')
}
</script>

<style scoped lang="scss"></style>
