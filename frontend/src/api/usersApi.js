// Base API URL for the users API
const API_BASE_URL = 'http://localhost:8080/api/users';

export const usersApi = {
  // Get current user profile
  getMe: async (getAccessToken) => {
    try {
      const token = await getAccessToken();
      const response = await fetch(`${API_BASE_URL}/me`, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      
      if (!response.ok) {
        if (response.status === 404) {
          // User not found, but authenticated
          return null;
        }
        throw new Error(`Failed to get user profile (status: ${response.status})`);
      }
      
      return response.json();
    } catch (error) {
      console.error('Error getting user profile:', error);
      return null;
    }
  },
  
  // Register a new user
  register: async (userData, getAccessToken) => {
    try {
      const token = await getAccessToken();
      console.log('Got token for user registration:', token ? 'Token received' : 'No token');
      
      const response = await fetch(API_BASE_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify(userData)
      });
      
      console.log('User registration response status:', response.status);
      
      if (!response.ok) {
        const errorText = await response.text();
        console.error('Registration error response:', errorText || 'No response body');
        throw new Error(errorText || `Failed to register user (status: ${response.status})`);
      }
      
      return response.json();
    } catch (error) {
      console.error('User registration API error:', error);
      throw error;
    }
  }
}; 