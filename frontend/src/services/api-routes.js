export const API_V1 = "/api/v1";

export const apiRoutes = Object.freeze({
  auth: Object.freeze({
    candidateRegistrations: `${API_V1}/auth/registrations`,
    recruiterRegistrations: `${API_V1}/auth/recruiter-registrations`,
    sessions: `${API_V1}/auth/sessions`,
    refreshSession: `${API_V1}/auth/sessions/refresh`,
    currentSession: `${API_V1}/auth/sessions/current`,
  }),
  users: Object.freeze({ me: `${API_V1}/users/me` }),
  jobs: Object.freeze({
    collection: `${API_V1}/jobs`,
    mine: `${API_V1}/jobs/mine`,
    byId: (id) => `${API_V1}/jobs/${encodeURIComponent(id)}`,
    applications: (id) => `${API_V1}/jobs/${encodeURIComponent(id)}/applications`,
    applicationStatus: (id) => `${API_V1}/jobs/${encodeURIComponent(id)}/application-status`,
  }),
  applications: Object.freeze({
    collection: `${API_V1}/applications`,
    byId: (id) => `${API_V1}/applications/${encodeURIComponent(id)}`,
    status: (id) => `${API_V1}/applications/${encodeURIComponent(id)}/status`,
    resume: (id) => `${API_V1}/applications/${encodeURIComponent(id)}/resume`,
  }),
  conversations: Object.freeze({
    collection: `${API_V1}/conversations`,
    unreadCount: `${API_V1}/conversations/unread-count`,
    byId: (id) => `${API_V1}/conversations/${encodeURIComponent(id)}`,
    messages: (id) => `${API_V1}/conversations/${encodeURIComponent(id)}/messages`,
  }),
  adminAggregation: Object.freeze({
    status: `${API_V1}/admin/ingestion/status`,
    history: `${API_V1}/admin/ingestion/history`,
    historyById: (id) => `${API_V1}/admin/ingestion/history/${encodeURIComponent(id)}`,
    conflicts: `${API_V1}/admin/ingestion/conflicts`,
    conflictResolution: (id) => `${API_V1}/admin/ingestion/conflicts/${encodeURIComponent(id)}/resolution`,
    sync: (provider) => `${API_V1}/admin/ingestion/${encodeURIComponent(provider)}/sync`,
  }),
});
