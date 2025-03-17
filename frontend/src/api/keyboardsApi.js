// Base API URL - should be configured based on your environment
const API_BASE_URL = '/api/keyboards'

export const keyboardsApi = {
  // Get all keyboards
  getAll: async () => {
    const response = await fetch(API_BASE_URL)
    return response.json()
  },

  // Get a single keyboard by ID
  getById: async (id) => {
    const response = await fetch(`${API_BASE_URL}/${id}`)
    return response.json()
  },

  // Create a new keyboard
  create: async (keyboardData) => {
    const response = await fetch(API_BASE_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(keyboardData),
    })
    return response.json()
  },

  // Update an existing keyboard
  update: async (id, keyboardData) => {
    const response = await fetch(`${API_BASE_URL}/${id}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(keyboardData),
    })
    return response.json()
  },

  // Delete a keyboard
  delete: async (id) => {
    const response = await fetch(`${API_BASE_URL}/${id}`, {
      method: 'DELETE',
    })
    return response.json()
  },
} 