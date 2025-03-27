import React from 'react';
import { render, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import App from '../App';
import { useAuth0 } from '@auth0/auth0-react';
import { usersApi } from '../api/usersApi';

// Mock the modules
jest.mock('@auth0/auth0-react', () => ({
  useAuth0: jest.fn(),
}));

// Mock react-router-dom without using requireActual
jest.mock('react-router-dom', () => ({
  BrowserRouter: ({ children }) => <div data-testid="router">{children}</div>,
  Routes: ({ children }) => <div data-testid="routes">{children}</div>,
  Route: ({ element, path }) => <div data-testid={`route-${path}`}>{element}</div>,
  Link: ({ children, to }) => <a href={to} data-testid="link">{children}</a>,
  useNavigate: () => jest.fn(),
}));

jest.mock('../api/usersApi', () => ({
  usersApi: {
    getMe: jest.fn(),
    register: jest.fn(),
  },
}));

// Mock all components used in App.jsx
jest.mock('../components/ProfileButton', () => () => <div data-testid="profile-button" />);
jest.mock('../components/LoginButton', () => () => <div data-testid="login-button" />);
jest.mock('../components/LogoutButton', () => () => <div data-testid="logout-button" />);
jest.mock('../components/KeyboardList', () => () => <div data-testid="keyboard-list" />);
jest.mock('../pages/NewKeyboardPage', () => () => <div data-testid="new-keyboard-page" />);
jest.mock('../pages/ProfilePage', () => () => <div data-testid="profile-page" />);
jest.mock('../pages/KeyboardDetailPage', () => () => <div data-testid="keyboard-detail-page" />);

describe('App Component (User Registration Flow)', () => {
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

  it('should not attempt registration when user is not authenticated', async () => {
    useAuth0.mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: null,
      getAccessTokenSilently: mockGetAccessTokenSilently,
    });

    render(<App />);

    await waitFor(() => {
      expect(usersApi.getMe).not.toHaveBeenCalled();
      expect(usersApi.register).not.toHaveBeenCalled();
    });
  });

  it('should attempt to get user info when authenticated', async () => {
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: mockUser,
      getAccessTokenSilently: mockGetAccessTokenSilently,
    });

    // Mock getMe to return null (user not found in backend)
    usersApi.getMe.mockResolvedValueOnce(null);
    // Mock register to return the user data
    usersApi.register.mockResolvedValueOnce(mockUser);

    await act(async () => {
      render(<App />);
    });

    await waitFor(() => {
      expect(usersApi.getMe).toHaveBeenCalledWith(mockGetAccessTokenSilently);
      expect(usersApi.register).toHaveBeenCalledWith(
        {
          id: mockUser.sub,
          name: mockUser.name,
          email: mockUser.email,
          pictureUrl: mockUser.picture,
        },
        mockGetAccessTokenSilently
      );
    });
  });

  it('should not register user if already exists in backend', async () => {
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: mockUser,
      getAccessTokenSilently: mockGetAccessTokenSilently,
    });

    // Mock getMe to return user (user already exists in backend)
    usersApi.getMe.mockResolvedValueOnce(mockUser);

    await act(async () => {
      render(<App />);
    });

    await waitFor(() => {
      expect(usersApi.getMe).toHaveBeenCalledWith(mockGetAccessTokenSilently);
      expect(usersApi.register).not.toHaveBeenCalled();
    });
  });

  it('should handle registration errors', async () => {
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: mockUser,
      getAccessTokenSilently: mockGetAccessTokenSilently,
    });

    // Mock getMe to return null (user not found)
    usersApi.getMe.mockResolvedValueOnce(null);
    // Mock register to throw an error
    const errorMessage = 'Failed to register user';
    usersApi.register.mockRejectedValueOnce(new Error(errorMessage));

    await act(async () => {
      render(<App />);
    });

    await waitFor(() => {
      expect(usersApi.getMe).toHaveBeenCalledWith(mockGetAccessTokenSilently);
      expect(usersApi.register).toHaveBeenCalled();
      expect(console.error).toHaveBeenCalled();
    });
  });
}); 