import { keyboardsApi } from '../../api/keyboardsApi';

// Mock fetch globally
global.fetch = jest.fn();
global.console.log = jest.fn();
global.console.error = jest.fn();

describe('keyboardsApi', () => {
  const API_BASE_URL = 'http://localhost:8080/api/keyboards';
  
  beforeEach(() => {
    jest.clearAllMocks();
    
    // Default successful response
    global.fetch.mockResolvedValue({
      ok: true,
      json: jest.fn().mockResolvedValue([]),
    });
  });
  
  describe('getByUserId', () => {
    const userId = 'b0796a1a-dcdd-4c5f-a84f-1374f1dd2cc1';
    const mockToken = 'mock-token';
    const mockGetAccessToken = jest.fn().mockResolvedValue(mockToken);
    const mockKeyboards = [
      { id: 1, name: 'Test Keyboard 1' },
      { id: 2, name: 'Test Keyboard 2' },
    ];
    
    test('should throw error if userId is not provided', async () => {
      await expect(keyboardsApi.getByUserId(null, mockGetAccessToken))
        .rejects
        .toThrow('User ID is required');
      
      await expect(keyboardsApi.getByUserId(undefined, mockGetAccessToken))
        .rejects
        .toThrow('User ID is required');
      
      await expect(keyboardsApi.getByUserId('', mockGetAccessToken))
        .rejects
        .toThrow('User ID is required');
    });
    
    test('should throw error if getAccessToken is not a function', async () => {
      await expect(keyboardsApi.getByUserId(userId, null))
        .rejects
        .toThrow('Authentication error: getAccessToken is not a function');
      
      await expect(keyboardsApi.getByUserId(userId, 'not-a-function'))
        .rejects
        .toThrow('Authentication error: getAccessToken is not a function');
      
      await expect(keyboardsApi.getByUserId(userId, {}))
        .rejects
        .toThrow('Authentication error: getAccessToken is not a function');
    });
    
    test('should successfully fetch keyboards for a user', async () => {
      global.fetch.mockResolvedValueOnce({
        ok: true,
        json: jest.fn().mockResolvedValue(mockKeyboards),
      });
      
      const result = await keyboardsApi.getByUserId(userId, mockGetAccessToken);
      
      expect(mockGetAccessToken).toHaveBeenCalledTimes(1);
      expect(global.fetch).toHaveBeenCalledWith(
        `${API_BASE_URL}/user/${userId}`,
        {
          headers: {
            'Authorization': `Bearer ${mockToken}`
          }
        }
      );
      expect(result).toEqual(mockKeyboards);
      expect(result.length).toBe(2);
    });
    
    test('should handle 401 unauthorized error', async () => {
      const errorResponse = {
        ok: false,
        status: 401,
        text: jest.fn().mockResolvedValue('Unauthorized'),
      };
      global.fetch.mockResolvedValueOnce(errorResponse);
      
      await expect(keyboardsApi.getByUserId(userId, mockGetAccessToken))
        .rejects
        .toThrow('Failed to get keyboards for user (status: 401): Unauthorized');
      
      expect(errorResponse.text).toHaveBeenCalled();
    });
    
    test('should handle 404 not found error', async () => {
      const errorResponse = {
        ok: false,
        status: 404,
        text: jest.fn().mockResolvedValue('Not Found'),
      };
      global.fetch.mockResolvedValueOnce(errorResponse);
      
      await expect(keyboardsApi.getByUserId(userId, mockGetAccessToken))
        .rejects
        .toThrow('Failed to get keyboards for user (status: 404): Not Found');
    });
    
    test('should handle network error', async () => {
      const networkError = new Error('Network error');
      global.fetch.mockRejectedValueOnce(networkError);
      
      await expect(keyboardsApi.getByUserId(userId, mockGetAccessToken))
        .rejects
        .toThrow('Network error');
    });
    
    test('should handle empty response', async () => {
      global.fetch.mockResolvedValueOnce({
        ok: true,
        json: jest.fn().mockResolvedValue([]),
      });
      
      const result = await keyboardsApi.getByUserId(userId, mockGetAccessToken);
      
      expect(result).toEqual([]);
      expect(result.length).toBe(0);
    });
  });
}); 