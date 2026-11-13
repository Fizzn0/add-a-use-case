// UC-STU-deactivate-student T01/T05/T07/T10/T14. Real Vue UI, stubbed HTTP;
// these are not database/authentication integration tests.
const result = (data, message = 'Success') => ({ flag: true, code: 200, message, data })
const student = {
  id: 5,
  username: 'j.smith@abc.edu',
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  enabled: true,
  teamId: 1,
  teamName: 'Team1',
  sectionId: 1,
  roles: 'student'
}
function visit(path, user = { ...student, id: 1, roles: 'admin instructor' }) {
  cy.visit(path, {
    onBeforeLoad(win) {
      const token = `e30.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))}.test`
      win.localStorage.setItem('token', JSON.stringify({ token }))
      win.localStorage.setItem('userInfo', JSON.stringify({ userInfo: user }))
      win.localStorage.setItem(
        'settings',
        JSON.stringify({ defaultSectionId: 1, defaultCourseId: 1 })
      )
    }
  })
}
describe('Student deactivation confirmation', () => {
  let current
  beforeEach(() => {
    current = { ...student }
    cy.viewport(1440, 1000)
    cy.intercept('POST', '**/students/search*', (req) =>
      req.reply(result({ content: [current], number: 0, size: 50, totalElements: 1 }))
    ).as('list')
    cy.intercept('GET', '**/students/5', (req) => req.reply(result(current))).as('detail')
    cy.intercept('PUT', '**/students/5', (req) =>
      req.reply(result({ ...current, ...req.body }))
    ).as('profile')
    cy.intercept('POST', '**/students/5/deactivate', (req) => {
      current = { ...current, enabled: false }
      req.reply({ delay: 500, body: result(current, 'Student deactivated successfully') })
    }).as('deactivate')
    visit('/students')
    cy.wait('@list')
    cy.get('.el-table__body .el-button').first().click()
    cy.wait('@detail')
  })
  function open() {
    cy.get('[data-cy=deactivate-open]').click()
  }
  function confirm() {
    cy.get('.el-message-box').contains('button', 'Deactivate student').click()
  }
  it('T01 warns before sending, then refreshes disabled status exactly once', () => {
    open()
    cy.get('.el-message-box')
      .should('contain', 'John Smith')
      .and('contain', 'existing login')
      .and('contain', 'reminders')
      .and('contain', 'new evaluations')
      .and('contain', 'Team membership')
      .and('contain', 'not deleted')
      .and('contain', 'weeks with saved evaluations')
    cy.get('@deactivate.all').should('have.length', 0)
    cy.screenshot('student-deactivation-warning')
    confirm()
    cy.get('[data-cy=deactivate-open]').should('be.disabled').click({ force: true })
    cy.wait('@deactivate')
    cy.get('.el-table__body').should('contain', 'Disabled')
    cy.get('@deactivate.all').should('have.length', 1)
    cy.get('@profile.all').should('have.length', 0)
  })
  for (const dismissal of ['Cancel', 'Escape', 'close']) {
    it(`T05 ${dismissal} sends nothing and preserves form edits`, () => {
      cy.get('.el-dialog input').eq(2).clear().type('Unsaved')
      open()
      if (dismissal === 'Cancel') cy.get('.el-message-box').contains('button', 'Cancel').click()
      else if (dismissal === 'Escape') cy.get('body').type('{esc}')
      else cy.get('.el-message-box__headerbtn').click()
      cy.get('[data-cy=deactivate-open]').should('be.enabled')
      cy.get('.el-dialog input').eq(2).should('have.value', 'Unsaved')
      cy.get('@deactivate.all').should('have.length', 0)
      cy.get('@profile.all').should('have.length', 0)
    })
  }
  for (const failure of ['server', 'network']) {
    it(`T07 ${failure} failure offers safe retry without success`, () => {
      let fail = true
      cy.intercept('POST', '**/students/5/deactivate', (req) => {
        if (fail)
          req.reply(
            failure === 'server'
              ? { statusCode: 500, body: { flag: false, message: 'Write failed' } }
              : { forceNetworkError: true }
          )
      }).as('failure')
      open()
      confirm()
      cy.wait('@failure')
      cy.get('[data-cy=deactivate-error]').should('contain', 'could not be confirmed')
      cy.get('.el-message--success').should('not.exist')
      cy.get('[data-cy=deactivate-open]').should('be.enabled')
      cy.then(() => {
        fail = false
      })
      open()
      confirm()
      cy.wait('@deactivate')
      cy.get('.el-table__body').should('contain', 'Disabled')
    })
  }
  it('T10 stale active profile never sends an activation flag', () => {
    cy.then(() => {
      current.enabled = false
    }) // another instructor deactivated after load
    cy.get('.el-dialog').contains('button', 'Confirm').click()
    cy.wait('@profile').its('request.body').should('not.have.property', 'enabled')
    cy.get('.el-table__body').should('contain', 'Disabled')
  })
})

describe('Inactive evaluation targets', () => {
  const inactive = { ...student, id: 6, firstName: 'Inactive', enabled: false }
  const saved = {
    evaluationId: 20,
    evaluatorId: 5,
    evaluateeId: 6,
    evaluateeName: 'Inactive Smith',
    ratings: [],
    publicComment: 'Saved work',
    privateComment: '',
    week: '2026-W45'
  }
  beforeEach(() => {
    cy.intercept('GET', '**/students/teams/1', result([student, inactive])).as('members')
    cy.intercept('GET', '**/sections/1', result({ rubricId: 1 }))
    cy.intercept('GET', '**/rubrics/1', result({ criteria: [] }))
    cy.intercept('GET', '**/evaluations/evaluators/5/week/*', result([])).as('saved')
  })
  it('T14 waits for partial writes before refresh and updates saved rows on retry', () => {
    let savedSelf
    cy.intercept('GET', '**/students/teams/1', result([student, { ...inactive, enabled: true }]))
    cy.intercept('POST', '**/evaluations', (req) => {
      if (req.body.evaluateeId === 6) {
        req.alias = 'inactiveRefused'
        req.reply({ statusCode: 400, body: { message: 'Student is inactive' } })
      } else {
        savedSelf = { ...req.body, evaluationId: 21 }
        req.alias = 'activeSaved'
        req.reply({ delay: 1200, body: result(savedSelf) })
      }
    }).as('writes')
    visit('/evaluations/submit-evaluations', student)
    cy.wait('@saved')
    cy.contains('button', 'Submit').click()
    cy.wait('@inactiveRefused')
    cy.get('[data-cy=evaluation-refresh-required]').should('not.exist')
    cy.wait('@activeSaved')
    cy.get('[data-cy=evaluation-refresh-required]').should('exist')
    cy.intercept('GET', '**/students/teams/1', result([student, inactive]))
    cy.intercept('GET', '**/evaluations/evaluators/5/week/*', (req) =>
      req.reply(result([savedSelf]))
    )
    cy.intercept('PUT', '**/evaluations/21', (req) => req.reply(result(req.body))).as('updateSaved')
    cy.contains('button', 'Refresh evaluations').click()
    cy.contains('button', 'Update').click()
    cy.wait('@updateSaved')
    cy.get('@writes.all').should('have.length', 2)
  })
  it('T14 omits inactive new targets but retains saved inactive rows', () => {
    visit('/evaluations/submit-evaluations', student)
    cy.wait('@saved')
    cy.get('.el-table__body').should('contain', 'John Smith').and('not.contain', 'Inactive Smith')
    cy.intercept('GET', '**/evaluations/evaluators/5/week/*', result([saved])).as('savedRows')
    cy.reload()
    cy.wait('@savedRows')
    cy.get('.el-table__body').should('contain', 'Inactive Smith')
    cy.get('textarea').should('have.value', 'Saved work')
  })
  it('T14 stale-target refusal prompts refresh with no success or receipt', () => {
    cy.intercept('POST', '**/evaluations', {
      statusCode: 400,
      body: {
        flag: false,
        message: 'This student is inactive. Refresh the evaluation list before submitting.'
      }
    }).as('rejected')
    cy.intercept('POST', '**/evaluations/weeks/*/receipt', result(null)).as('receipt')
    visit('/evaluations/submit-evaluations', student)
    cy.wait('@saved')
    cy.contains('button', 'Submit').click()
    cy.wait('@rejected')
    cy.get('[data-cy=evaluation-refresh-required]').scrollIntoView().should('be.visible')
    cy.contains('button', 'Submit').should('be.disabled')
    cy.get('.el-message--success').should('not.exist')
    cy.get('@receipt.all').should('have.length', 0)
    cy.intercept('GET', '**/students/teams/1', result([{ ...student, enabled: false }, inactive]))
    cy.contains('button', 'Refresh evaluations').click()
    cy.get('[data-cy=evaluation-refresh-required]').should('not.exist')
    cy.get('.el-empty').should('be.visible')
  })
})

it('T10 ordinary student self-profile save omits lifecycle state', () => {
  cy.intercept('PUT', '**/students/5', result({ ...student, firstName: 'Changed' })).as(
    'selfProfile'
  )
  visit('/user/profile', student)
  cy.get('input').eq(2).clear().type('Changed')
  cy.contains('button', 'Update').click()
  cy.wait('@selfProfile').its('request.body').should('not.have.property', 'enabled')
  cy.contains('User updated successfully').should('be.visible')
})
