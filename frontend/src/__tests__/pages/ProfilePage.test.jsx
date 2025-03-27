import React from 'react';
import { render, screen, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import ProfilePage from '../../pages/ProfilePage';
import { useAuth0 } from '@auth0/auth0-react';
import { keyboardsApi } from '../../api/keyboardsApi';

// Mock modules
jest.mock('@auth0/auth0-react');
jest.mock('react-router-dom', () => ({
  useNavigate: () => jest.fn(),
}));
jest.mock('../../components/LoginButton', () => () => <div data-testid="login-button" />);
jest.mock('../../components/SafeImage', () => ({ src, alt }) => <img src={src} alt={alt} data-testid="safe-image" />);
jest.mock('../../api/keyboardsApi');
jest.mock('../../components/ErrorDisplay', () => ({ errorMessage, onRetry }) => (
  <div data-testid="error-display" onClick={onRetry}>
    {errorMessage}
  </div>
));

// Mock global fetch function
global.fetch = jest.fn();

describe('ProfilePage Component', () => {
  const mockUser = {
    sub: 'google-oauth2|123456789',
    name: 'Test User',
    email: 'test@example.com',
    picture: 'https://example.com/avatar.jpg',
  };

  const mockUserProfile = {
    id: 'b0796a1a-dcdd-4c5f-a84f-1374f1dd2cc1',
    authProviderId: 'google-oauth2|123456789',
    name: 'Test User',
    email: 'test@example.com',
    pictureUrl: 'https://example.com/avatar.jpg',
  };

  const mockKeyboards = [
    {
      id: 1,
      name: 'Test Keyboard 1',
      split: true,
      hotswap: false,
    },
    {
      id: 2,
      name: 'Test Keyboard 2',
      split: false,
      hotswap: true,
    },
  ];

  const mockGetAccessTokenSilently = jest.fn().mockResolvedValue('test-token');

  beforeEach(() => {
    // Clear all mocks before each test
    jest.clearAllMocks();
    global.fetch.mockClear();

    // Mock useAuth0 default return value
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: mockUser,
      getAccessTokenSilently: mockGetAccessTokenSilently,
    });

    // Mock keyboardsApi.getByUserId default implementation
    keyboardsApi.getByUserId.mockResolvedValue(mockKeyboards);

    // Mock successful fetch response for /api/users/me
    global.fetch.mockResolvedValue({
      ok: true,
      json: jest.fn().mockResolvedValue(mockUserProfile),
    });
  });

  test('renders loader when Auth0 is loading', () => {
    useAuth0.mockReturnValue({
      isAuthenticated: false,
      isLoading: true,
      user: null,
    });

    render(<ProfilePage />);
    // Check for the loader element directly
    expect(document.querySelector('.ui.active.centered.inline.loader')).toBeInTheDocument();
  });

  test('displays message and login button when user is not authenticated', () => {
    useAuth0.mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: null,
    });

    render(<ProfilePage />);
    expect(screen.getByText(/not logged in/i)).toBeInTheDocument();
    expect(screen.getByTestId('login-button')).toBeInTheDocument();
  });

  test('fetches and displays user profile and keyboards when authenticated', async () => {
    await act(async () => {
      render(<ProfilePage />);
    });

    // Check if user profile is displayed
    expect(screen.getByText(mockUser.name)).toBeInTheDocument();
    expect(screen.getByText(mockUser.email)).toBeInTheDocument();

    // Verify API calls
    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith(
        'http://localhost:8080/api/users/me',
        expect.objectContaining({
          headers: {
            'Authorization': 'Bearer test-token'
          }
        })
      );
      expect(keyboardsApi.getByUserId).toHaveBeenCalledWith(
        mockUserProfile.id,
        mockGetAccessTokenSilently
      );
    });

    // Check if keyboards are displayed
    await waitFor(() => {
      expect(screen.getByText('Test Keyboard 1')).toBeInTheDocument();
      expect(screen.getByText('Test Keyboard 2')).toBeInTheDocument();
    });
  });

  test('displays message when user has no keyboards', async () => {
    // Mock empty keyboards array
    keyboardsApi.getByUserId.mockResolvedValue([]);

    await act(async () => {
      render(<ProfilePage />);
    });

    await waitFor(() => {
      expect(screen.getByText(/no keyboards found/i)).toBeInTheDocument();
      expect(screen.getByText(/you haven't submitted any keyboards yet/i)).toBeInTheDocument();
    });
  });

  test('displays error message when fetching user profile fails', async () => {
    // Mock failed fetch for user profile
    global.fetch.mockResolvedValue({
      ok: false,
      status: 404,
    });

    await act(async () => {
      render(<ProfilePage />);
    });

    await waitFor(() => {
      expect(screen.getByTestId('error-display')).toBeInTheDocument();
      expect(screen.getByText(/the requested resource was not found/i)).toBeInTheDocument();
    });
  });

  test('displays error message when fetching keyboards fails with 401', async () => {
    // Mock successful fetch for user profile
    global.fetch.mockResolvedValue({
      ok: true,
      json: jest.fn().mockResolvedValue(mockUserProfile),
    });

    // Mock 401 error for keyboard API
    keyboardsApi.getByUserId.mockRejectedValue(new Error('Failed to get keyboards for user (status: 401)'));

    await act(async () => {
      render(<ProfilePage />);
    });

    await waitFor(() => {
      expect(screen.getByTestId('error-display')).toBeInTheDocument();
      expect(screen.getByText(/authentication error/i)).toBeInTheDocument();
    });
  });

  test('displays appropriate error for 404 fetching keyboards', async () => {
    keyboardsApi.getByUserId.mockRejectedValue(new Error('Failed to get keyboards for user (status: 404)'));

    await act(async () => {
      render(<ProfilePage />);
    });

    await waitFor(() => {
      expect(screen.getByText(/the requested resource was not found/i)).toBeInTheDocument();
    });
  });

  test('displays appropriate error for 500 fetching keyboards', async () => {
    keyboardsApi.getByUserId.mockRejectedValue(new Error('Failed to get keyboards for user (status: 500)'));

    await act(async () => {
      render(<ProfilePage />);
    });

    await waitFor(() => {
      expect(screen.getByText(/server error/i)).toBeInTheDocument();
    });
  });

  test('retries fetching keyboards when retry button is clicked', async () => {
    // First fail, then succeed
    keyboardsApi.getByUserId
      .mockRejectedValueOnce(new Error('Failed to get keyboards for user (status: 500)'))
      .mockResolvedValueOnce(mockKeyboards);

    await act(async () => {
      render(<ProfilePage />);
    });

    // Check for error display
    await waitFor(() => {
      expect(screen.getByTestId('error-display')).toBeInTheDocument();
    });

    // Click retry button
    await act(async () => {
      screen.getByTestId('error-display').click();
    });

    // Verify the API was called again
    await waitFor(() => {
      expect(keyboardsApi.getByUserId).toHaveBeenCalledTimes(2);
      expect(screen.queryByTestId('error-display')).not.toBeInTheDocument();
      expect(screen.getByText('Test Keyboard 1')).toBeInTheDocument();
    });
  });
}); 