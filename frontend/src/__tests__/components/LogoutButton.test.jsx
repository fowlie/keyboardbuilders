import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';
import LogoutButton from '../../components/LogoutButton';
import { useAuth0 } from '@auth0/auth0-react';

// Mock the Auth0 hook
jest.mock('@auth0/auth0-react', () => ({
  useAuth0: jest.fn(),
}));

describe('LogoutButton Component', () => {
  // Mock implementation setup
  const logoutMock = jest.fn();

  beforeEach(() => {
    // Clear all mocks before each test
    jest.clearAllMocks();
    
    // Setup default mock return values
    useAuth0.mockReturnValue({
      logout: logoutMock,
    });
  });

  it('renders logout button correctly', () => {
    render(<LogoutButton />);
    
    const logoutButton = screen.getByRole('button', { name: /log out/i });
    expect(logoutButton).toBeInTheDocument();
    expect(logoutButton).toHaveClass('ui button');
  });

  it('calls logout with correct params when clicked', () => {
    render(<LogoutButton />);
    
    const logoutButton = screen.getByRole('button', { name: /log out/i });
    fireEvent.click(logoutButton);
    
    expect(logoutMock).toHaveBeenCalledTimes(1);
    expect(logoutMock).toHaveBeenCalledWith({ 
      logoutParams: { returnTo: window.location.origin } 
    });
  });
}); 