// Base API URL - should be configured based on your environment
const API_BASE_URL = 'http://localhost:8080/api/keyboards'

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
  create: async (keyboardData, getAccessToken) => {
    try {
      const token = await getAccessToken()
      console.log('Got token:', token ? 'Token received' : 'No token')
      
      const response = await fetch(API_BASE_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify(keyboardData),
      })
      
      console.log('Response status:', response.status)
      
      if (!response.ok) {
        const errorText = await response.text()
        console.error('Error response:', errorText || 'No response body')
        throw new Error(errorText || `Failed to create keyboard (status: ${response.status})`)
      }
      
      return response.json()
    } catch (error) {
      console.error('API error:', error)
      throw error
    }
  },

  // Update an existing keyboard
  update: async (id, keyboardData, getAccessToken) => {
    const token = await getAccessToken()
    const response = await fetch(`${API_BASE_URL}/${id}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(keyboardData),
    })
    
    if (!response.ok) {
      const error = await response.text()
      throw new Error(error || 'Failed to update keyboard')
    }
    
    return response.json()
  },

  // Delete a keyboard
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
      throw new Error(error || 'Failed to delete keyboard')
    }
    
    return response.json()
  },

  // Get keyboards for a specific user
  getByUserId: async (userId, getAccessToken) => {
    try {
      console.log('Fetching keyboards for user with ID:', userId);
      
      // Validate userId
      if (!userId) {
        throw new Error('User ID is required');
      }
      
      // Make sure we have the getAccessToken function
      if (typeof getAccessToken !== 'function') {
        console.error('getAccessToken is not a function:', getAccessToken);
        throw new Error('Authentication error: getAccessToken is not a function');
      }
      
      const token = await getAccessToken();
      console.log('Got auth token for user keyboards request:', !!token);
      
      const url = `${API_BASE_URL}/user/${userId}`;
      console.log('Requesting from URL:', url);
      
      const response = await fetch(url, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      
      console.log('Response status:', response.status);
      
      if (!response.ok) {
        const errorText = await response.text();
        console.error('Error response body:', errorText || 'No response body');
        throw new Error(`Failed to get keyboards for user (status: ${response.status})${errorText ? ': ' + errorText : ''}`);
      }
      
      const data = await response.json();
      console.log('Successfully retrieved keyboards for user, count:', data.length);
      return data;
    } catch (error) {
      console.error('Error getting user keyboards:', error);
      throw error;
    }
  },
} 