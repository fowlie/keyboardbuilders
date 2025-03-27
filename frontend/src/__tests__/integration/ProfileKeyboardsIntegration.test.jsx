import React from 'react';
import { render, screen, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import ProfilePage from '../../pages/ProfilePage';
import { useAuth0 } from '@auth0/auth0-react';
import { keyboardsApi } from '../../api/keyboardsApi';

// Integration test to verify the proper data flow between ProfilePage and keyboardsApi

// Mock modules
jest.mock('@auth0/auth0-react');
jest.mock('react-router-dom', () => ({
  useNavigate: () => jest.fn(),
}));
jest.mock('../../components/LoginButton', () => () => <div data-testid="login-button" />);
jest.mock('../../components/SafeImage', () => ({ src, alt }) => <img src={src} alt={alt} data-testid="safe-image" />);
jest.mock('../../components/ErrorDisplay', () => ({ errorMessage, onRetry }) => (
  <div data-testid="error-display" onClick={onRetry}>
    {errorMessage}
  </div>
));

// We only partially mock the keyboardsApi to test the actual function implementation
jest.mock('../../api/keyboardsApi', () => {
  const originalModule = jest.requireActual('../../api/keyboardsApi');
  return {
    ...originalModule,
    keyboardsApi: {
      ...originalModule.keyboardsApi,
      // Only mock the getByUserId method to spy on calls, but use the actual implementation
      getByUserId: jest.fn(originalModule.keyboardsApi.getByUserId),
    },
  };
});

// Mock fetch globally
global.fetch = jest.fn();

describe('Profile Page and keyboardsApi Integration', () => {
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
  ];

  const mockGetAccessTokenSilently = jest.fn().mockResolvedValue('test-token');

  beforeEach(() => {
    // Clear all mocks before each test
    jest.clearAllMocks();
    global.fetch.mockClear();
    keyboardsApi.getByUserId.mockClear();

    // Mock useAuth0 default return value
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: mockUser,
      getAccessTokenSilently: mockGetAccessTokenSilently,
    });

    // Mock fetch response for user profile (success case)
    global.fetch.mockImplementation((url) => {
      if (url === 'http://localhost:8080/api/users/me') {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve(mockUserProfile),
        });
      }
      
      // For keyboards API (we'll test this separately with the actual implementation)
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve(mockKeyboards),
      });
    });
  });

  test('correctly passes the UUID from user profile to keyboardsApi.getByUserId', async () => {
    // Modify the keyboardsApi.getByUserId mock to resolve with mock data
    keyboardsApi.getByUserId.mockResolvedValueOnce(mockKeyboards);

    await act(async () => {
      render(<ProfilePage />);
    });

    // First, verify that the user profile API was called
    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith(
        'http://localhost:8080/api/users/me',
        expect.objectContaining({
          headers: {
            'Authorization': 'Bearer test-token',
          },
        })
      );
    });

    // Then verify that keyboardsApi.getByUserId was called with the correct UUID
    await waitFor(() => {
      expect(keyboardsApi.getByUserId).toHaveBeenCalledWith(
        mockUserProfile.id, // Should be using the UUID, not the Auth0 sub
        mockGetAccessTokenSilently
      );
    });

    // Finally, verify the keyboards are displayed
    await waitFor(() => {
      expect(screen.getByText('Test Keyboard 1')).toBeInTheDocument();
    });
  });

  test('handles API error gracefully when user profile fetch fails', async () => {
    // Simulate a failed API call for user profile
    global.fetch.mockImplementationOnce(() => 
      Promise.resolve({
        ok: false,
        status: 500,
        text: () => Promise.resolve('Internal Server Error'),
      })
    );

    await act(async () => {
      render(<ProfilePage />);
    });

    // Verify the error is displayed
    await waitFor(() => {
      expect(screen.getByTestId('error-display')).toBeInTheDocument();
      expect(screen.getByText(/server error. please try again later/i)).toBeInTheDocument();
    });

    // Verify keyboardsApi.getByUserId was NOT called since user profile failed
    expect(keyboardsApi.getByUserId).not.toHaveBeenCalled();
  });

  test('retries the full fetch process when retry button is clicked', async () => {
    // First request fails
    global.fetch.mockImplementationOnce(() => 
      Promise.resolve({
        ok: false,
        status: 500,
        text: () => Promise.resolve('Internal Server Error'),
      })
    );

    // Second request succeeds
    global.fetch.mockImplementationOnce(() => 
      Promise.resolve({
        ok: true,
        json: () => Promise.resolve(mockUserProfile),
      })
    );

    keyboardsApi.getByUserId.mockResolvedValueOnce(mockKeyboards);

    await act(async () => {
      render(<ProfilePage />);
    });

    // Verify error is shown
    await waitFor(() => {
      expect(screen.getByTestId('error-display')).toBeInTheDocument();
    });

    // Click retry
    await act(async () => {
      screen.getByTestId('error-display').click();
    });

    // Verify profile and keyboards were fetched successfully on retry
    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalledTimes(2);
      expect(keyboardsApi.getByUserId).toHaveBeenCalledTimes(1);
      expect(keyboardsApi.getByUserId).toHaveBeenCalledWith(
        mockUserProfile.id,
        mockGetAccessTokenSilently
      );
      expect(screen.getByText('Test Keyboard 1')).toBeInTheDocument();
    });
  });
}); 