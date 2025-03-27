// Base API URL - should be configured based on your environment
const API_BASE_URL = 'http://localhost:8080/api/controllers'

export const controllersApi = {
  // Get all controllers
  getAll: async () => {
    const response = await fetch(API_BASE_URL)
    return response.json()
  },

  // Get a single controller by ID
  getById: async (id) => {
    const response = await fetch(`${API_BASE_URL}/${id}`)
    return response.json()
  },

  // Create a new controller
  create: async (controllerData, getAccessToken) => {
    try {
      const token = await getAccessToken()
      
      const response = await fetch(API_BASE_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify(controllerData),
      })
      
      if (!response.ok) {
        const errorText = await response.text()
        throw new Error(errorText || `Failed to create controller (status: ${response.status})`)
      }
      
      return response.json()
    } catch (error) {
      console.error('API error:', error)
      throw error
    }
  },

  // Update an existing controller
  update: async (id, controllerData, getAccessToken) => {
    const token = await getAccessToken()
    const response = await fetch(`${API_BASE_URL}/${id}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(controllerData),
    })
    
    if (!response.ok) {
      const error = await response.text()
      throw new Error(error || 'Failed to update controller')
    }
    
    return response.json()
  },

  // Delete a controller
  delete: async (id, getAccessToken) => {
    const token = await getAccessToken()
    const response = await fetch(`${API_BASE_URL}/${id}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })
    
    if (!response.ok) {
      const error = await response.text()
      throw new Error(error || 'Failed to delete controller')
    }
  },

  // Get controllers for a specific user
  getByUserId: async (userId, getAccessToken) => {
    try {
      // Validate userId
      if (!userId) {
        throw new Error('User ID is required');
      }
      
      const token = await getAccessToken();
      
      const url = `${API_BASE_URL}/user/${userId}`;
      
      const response = await fetch(url, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Failed to get controllers for user (status: ${response.status})${errorText ? ': ' + errorText : ''}`);
      }
      
      return await response.json();
    } catch (error) {
      console.error('Error getting user controllers:', error);
      throw error;
    }
  },
} 