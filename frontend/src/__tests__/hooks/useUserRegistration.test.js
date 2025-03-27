import { renderHook, act } from '@testing-library/react';
import { useUserRegistration } from '../../hooks/useUserRegistration';
import { useAuth0 } from '@auth0/auth0-react';
import { usersApi } from '../../api/usersApi';

// Mock the Auth0 hook
jest.mock('@auth0/auth0-react', () => ({
  useAuth0: jest.fn()
}));

// Mock the users API
jest.mock('../../api/usersApi', () => ({
  usersApi: {
    getMe: jest.fn(),
    register: jest.fn()
  }
}));

// Spy on console methods
const consoleLogSpy = jest.spyOn(console, 'log').mockImplementation();
const consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation();

describe('useUserRegistration', () => {
  beforeEach(() => {
    // Reset all mocks
    jest.clearAllMocks();
  });

  afterAll(() => {
    // Restore console methods
    consoleLogSpy.mockRestore();
    consoleErrorSpy.mockRestore();
  });

  test('should return loading state initially', async () => {
    // Mock Auth0 hook with loading state
    useAuth0.mockReturnValue({
      isAuthenticated: false,
      isLoading: true,
      getAccessTokenSilently: jest.fn(),
      user: null
    });

    const { result } = renderHook(() => useUserRegistration());

    expect(result.current).toEqual({
      userRegistered: false,
      userLoading: true,
      userError: null
    });
  });

  test('should set userRegistered to false when not authenticated', async () => {
    // Mock Auth0 hook with not authenticated state
    useAuth0.mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      getAccessTokenSilently: jest.fn(),
      user: null
    });

    const { result, rerender } = renderHook(() => useUserRegistration());
    
    // Wait for the next update
    await act(async () => {
      // Just triggering a re-render after the effect runs
      rerender();
    });

    expect(result.current).toEqual({
      userRegistered: false,
      userLoading: false,
      userError: null
    });
  });

  test('should register new user when user does not exist in database', async () => {
    // Mock Auth0 hook with authenticated user
    const mockUser = {
      sub: 'auth0|123456',
      name: 'Test User',
      email: 'test@example.com',
      picture: 'https://example.com/picture.jpg'
    };
    
    const mockGetToken = jest.fn().mockResolvedValue('mock-token');
    
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      getAccessTokenSilently: mockGetToken,
      user: mockUser
    });

    // Mock API responses
    usersApi.getMe.mockResolvedValue(null); // User not found
    usersApi.register.mockResolvedValue({ id: mockUser.sub, name: mockUser.name }); // Registration successful

    const { result, rerender } = renderHook(() => useUserRegistration());
    
    // Wait for the async operations to complete
    await act(async () => {
      // Just triggering a re-render after the effect runs
      rerender();
    });

    expect(usersApi.getMe).toHaveBeenCalled();
    expect(usersApi.register).toHaveBeenCalledWith(
      {
        id: mockUser.sub,
        name: mockUser.name,
        email: mockUser.email,
        pictureUrl: mockUser.picture
      },
      mockGetToken
    );

    expect(result.current).toEqual({
      userRegistered: true,
      userLoading: false,
      userError: null
    });
  });

  test('should not register when user already exists in database', async () => {
    // Mock Auth0 hook with authenticated user
    const mockUser = {
      sub: 'auth0|123456',
      name: 'Test User',
      email: 'test@example.com',
      picture: 'https://example.com/picture.jpg'
    };
    
    const mockGetToken = jest.fn().mockResolvedValue('mock-token');
    
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      getAccessTokenSilently: mockGetToken,
      user: mockUser
    });

    // Mock API responses
    usersApi.getMe.mockResolvedValue({ id: mockUser.sub, name: mockUser.name }); // User found

    const { result, rerender } = renderHook(() => useUserRegistration());
    
    // Wait for the async operations to complete
    await act(async () => {
      // Just triggering a re-render after the effect runs
      rerender();
    });

    expect(usersApi.getMe).toHaveBeenCalled();
    expect(usersApi.register).not.toHaveBeenCalled(); // Should not register existing user

    expect(result.current).toEqual({
      userRegistered: true,
      userLoading: false,
      userError: null
    });
  });

  test('should handle error during user check', async () => {
    // Mock Auth0 hook with authenticated user
    const mockUser = {
      sub: 'auth0|123456',
      name: 'Test User',
      email: 'test@example.com'
    };
    
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      getAccessTokenSilently: jest.fn().mockResolvedValue('mock-token'),
      user: mockUser
    });

    // Mock API error
    const errorMessage = 'API Error';
    usersApi.getMe.mockRejectedValue(new Error(errorMessage));

    const { result, rerender } = renderHook(() => useUserRegistration());
    
    // Wait for the async operations to complete
    await act(async () => {
      // Just triggering a re-render after the effect runs
      rerender();
    });

    expect(usersApi.getMe).toHaveBeenCalled();
    expect(usersApi.register).not.toHaveBeenCalled();

    expect(result.current).toEqual({
      userRegistered: false,
      userLoading: false,
      userError: errorMessage
    });
  });

  test('should handle error during user registration', async () => {
    // Mock Auth0 hook with authenticated user
    const mockUser = {
      sub: 'auth0|123456',
      name: 'Test User',
      email: 'test@example.com',
      picture: 'https://example.com/picture.jpg'
    };
    
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      getAccessTokenSilently: jest.fn().mockResolvedValue('mock-token'),
      user: mockUser
    });

    // Mock API responses
    usersApi.getMe.mockResolvedValue(null); // User not found
    const errorMessage = 'Registration Error';
    usersApi.register.mockRejectedValue(new Error(errorMessage));

    const { result, rerender } = renderHook(() => useUserRegistration());
    
    // Wait for the async operations to complete
    await act(async () => {
      // Just triggering a re-render after the effect runs
      rerender();
    });

    expect(usersApi.getMe).toHaveBeenCalled();
    expect(usersApi.register).toHaveBeenCalled();

    expect(result.current).toEqual({
      userRegistered: false,
      userLoading: false,
      userError: errorMessage
    });
  });
}); 