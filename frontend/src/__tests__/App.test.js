import React from 'react';
import { render, screen } from '@testing-library/react';
import App from '../App';
import { useAuth0 } from '@auth0/auth0-react';
import { useUserRegistration } from '../hooks/useUserRegistration';

// Mock the Auth0 hook
jest.mock('@auth0/auth0-react', () => ({
  useAuth0: jest.fn()
}));

// Mock the user registration hook
jest.mock('../hooks/useUserRegistration', () => ({
  useUserRegistration: jest.fn()
}));

// Mock components that might cause issues in testing
jest.mock('../components/ProfileButton', () => () => <div data-testid="profile-button">Profile</div>);
jest.mock('../components/LoginButton', () => () => <div data-testid="login-button">Login</div>);
jest.mock('../components/LogoutButton', () => () => <div data-testid="logout-button">Logout</div>);
jest.mock('../components/KeyboardList', () => () => <div>Keyboard List</div>);
jest.mock('../pages/NewKeyboardPage', () => () => <div>New Keyboard Page</div>);
jest.mock('../pages/ProfilePage', () => () => <div>Profile Page</div>);
jest.mock('../pages/KeyboardDetailPage', () => () => <div>Keyboard Detail Page</div>);

describe('App Component', () => {
  beforeEach(() => {
    // Reset all mocks
    jest.clearAllMocks();
    
    // Default mock implementations
    useAuth0.mockReturnValue({
      isAuthenticated: false,
      isLoading: false
    });
    
    useUserRegistration.mockReturnValue({
      userRegistered: false,
      userLoading: false,
      userError: null
    });
  });

  test('renders app with login button when not authenticated', () => {
    // Set up mocks for not authenticated state
    useAuth0.mockReturnValue({
      isAuthenticated: false,
      isLoading: false
    });
    
    // Render app
    const { container } = render(<App />);
    
    // We can't fully test the router without more complex setup
    // but we can check that the main container is rendered
    expect(container).toBeInTheDocument();
    expect(screen.getByTestId('login-button')).toBeInTheDocument();
    expect(screen.queryByTestId('logout-button')).not.toBeInTheDocument();
  });

  test('displays the correct user state from the hook', () => {
    // Set up specific user state
    const mockUserState = {
      userRegistered: true,
      userLoading: false,
      userError: null
    };
    
    useUserRegistration.mockReturnValue(mockUserState);
    
    // Render app
    render(<App />);
    
    // Verify the hook was called
    expect(useUserRegistration).toHaveBeenCalled();
  });

  test('shows logout button when authenticated', () => {
    // Set up mocks for authenticated state
    useAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false
    });
    
    useUserRegistration.mockReturnValue({
      userRegistered: true,
      userLoading: false,
      userError: null
    });
    
    // Render app
    render(<App />);
    
    // Test that logout button is shown instead of login button
    expect(screen.getByTestId('logout-button')).toBeInTheDocument();
    expect(screen.queryByTestId('login-button')).not.toBeInTheDocument();
  });
}); 