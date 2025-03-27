import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';
import LoginButton from '../../components/LoginButton';
import { useAuth0 } from '@auth0/auth0-react';

// Mock the Auth0 hook
jest.mock('@auth0/auth0-react', () => ({
  useAuth0: jest.fn(),
}));

describe('LoginButton Component', () => {
  // Mock implementation setup
  const loginWithRedirectMock = jest.fn();

  beforeEach(() => {
    // Clear all mocks before each test
    jest.clearAllMocks();
    
    // Setup default mock return values
    useAuth0.mockReturnValue({
      loginWithRedirect: loginWithRedirectMock,
    });
  });

  it('renders login button correctly', () => {
    render(<LoginButton />);
    
    const loginButton = screen.getByRole('button', { name: /log in/i });
    expect(loginButton).toBeInTheDocument();
    expect(loginButton).toHaveClass('ui primary button');
  });

  it('calls loginWithRedirect when clicked', () => {
    render(<LoginButton />);
    
    const loginButton = screen.getByRole('button', { name: /log in/i });
    fireEvent.click(loginButton);
    
    expect(loginWithRedirectMock).toHaveBeenCalledTimes(1);
  });
}); 