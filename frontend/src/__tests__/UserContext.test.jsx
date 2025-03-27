import React, { useContext } from 'react';
import { render, screen, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import App, { UserContext } from '../App';
import { useAuth0 } from '@auth0/auth0-react';
import { usersApi } from '../api/usersApi';

// Create a test component that consumes the context
const TestConsumer = () => {
  const { userRegistered, userLoading, userError } = useContext(UserContext);
  return (
    <div>
      <div data-testid="user-registered">{userRegistered.toString()}</div>
      <div data-testid="user-loading">{userLoading.toString()}</div>
      <div data-testid="user-error">{userError || 'no error'}</div>
    </div>
  );
};

// Mock the modules
jest.mock('@auth0/auth0-react', () => ({
  useAuth0: jest.fn(),
}));

jest.mock('react-router-dom', () => ({
  BrowserRouter: ({ children }) => <>{children}</>,
  Routes: ({ children }) => <>{children}</>,
  Route: ({ element }) => <>{element}</>,
  Link: ({ children }) => <>{children}</>,
  useNavigate: () => jest.fn(),
}));

jest.mock('../api/usersApi', () => ({
  usersApi: {
    getMe: jest.fn(),
    register: jest.fn(),
  },
}));

describe('UserContext', () => {
  const mockUser = {
    sub: 'auth0|123',
    name: 'Test User',
    email: 'test@example.com',
    picture: 'https://example.com/avatar.jpg',
  };

  const mockGetAccessTokenSilently = jest.fn().mockResolvedValue('test-token');

  beforeEach(() => {
    // Clear all mocks before each test
    jest.clearAllMocks();
    console.log = jest.fn();
    console.error = jest.fn();
  });

  it('should set userLoading to true initially', async () => {
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: true,
      user: null,
      getAccessTokenSilently: mockGetAccessTokenSilently,
    });

    await act(async () => {
      render(
        <>
          <App />
          <TestConsumer />
        </>
      );
    });

    expect(screen.getByTestId('user-loading').textContent).toBe('true');
  });

  it('should set userRegistered to true after successful registration', async () => {
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: mockUser,
      getAccessTokenSilently: mockGetAccessTokenSilently,
    });

    // User not found, then created
    usersApi.getMe.mockResolvedValueOnce(null);
    usersApi.register.mockResolvedValueOnce(mockUser);

    await act(async () => {
      render(
        <>
          <App />
          <TestConsumer />
        </>
      );
    });

    await waitFor(() => {
      expect(screen.getByTestId('user-registered').textContent).toBe('true');
      expect(screen.getByTestId('user-loading').textContent).toBe('false');
      expect(screen.getByTestId('user-error').textContent).toBe('no error');
    });
  });

  it('should set userRegistered to true when user exists', async () => {
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: mockUser,
      getAccessTokenSilently: mockGetAccessTokenSilently,
    });

    // User already exists
    usersApi.getMe.mockResolvedValueOnce(mockUser);

    await act(async () => {
      render(
        <>
          <App />
          <TestConsumer />
        </>
      );
    });

    await waitFor(() => {
      expect(screen.getByTestId('user-registered').textContent).toBe('true');
      expect(screen.getByTestId('user-loading').textContent).toBe('false');
      expect(screen.getByTestId('user-error').textContent).toBe('no error');
    });
  });

  it('should set userError when registration fails', async () => {
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: mockUser,
      getAccessTokenSilently: mockGetAccessTokenSilently,
    });

    // User not found, then error during registration
    usersApi.getMe.mockResolvedValueOnce(null);
    const errorMessage = 'Failed to register user';
    usersApi.register.mockRejectedValueOnce(new Error(errorMessage));

    await act(async () => {
      render(
        <>
          <App />
          <TestConsumer />
        </>
      );
    });

    await waitFor(() => {
      expect(screen.getByTestId('user-registered').textContent).toBe('false');
      expect(screen.getByTestId('user-loading').textContent).toBe('false');
      expect(screen.getByTestId('user-error').textContent).toBe(errorMessage);
    });
  });
}); 