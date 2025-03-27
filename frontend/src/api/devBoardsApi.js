// Base API URL - should be configured based on your environment
const API_BASE_URL = 'http://localhost:8080/api/devboards'

export const devBoardsApi = {
  // Get all dev boards
  getAll: async () => {
    const response = await fetch(API_BASE_URL)
    return response.json()
  },

  // Get a single dev board by ID
  getById: async (id) => {
    const response = await fetch(`${API_BASE_URL}/${id}`)
    return response.json()
  },

  // Create a new dev board
  create: async (devBoardData, getAccessToken) => {
    try {
      const token = await getAccessToken()
      
      const response = await fetch(API_BASE_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify(devBoardData),
      })
      
      if (!response.ok) {
        const errorText = await response.text()
        throw new Error(errorText || `Failed to create dev board (status: ${response.status})`)
      }
      
      return response.json()
    } catch (error) {
      console.error('API error:', error)
      throw error
    }
  },

  // Update an existing dev board
  update: async (id, devBoardData, getAccessToken) => {
    const token = await getAccessToken()
    const response = await fetch(`${API_BASE_URL}/${id}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(devBoardData),
    })
    
    if (!response.ok) {
      const error = await response.text()
      throw new Error(error || 'Failed to update dev board')
    }
    
    return response.json()
  },

  // Delete a dev board
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
      throw new Error(error || 'Failed to delete dev board')
    }
  },

  // Get dev boards for a specific user
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
        throw new Error(`Failed to get dev boards for user (status: ${response.status})${errorText ? ': ' + errorText : ''}`);
      }
      
      return await response.json();
    } catch (error) {
      console.error('Error getting user dev boards:', error);
      throw error;
    }
  },
} 