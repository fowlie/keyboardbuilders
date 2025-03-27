import React from 'react';
import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import ProfileButton from '../../components/ProfileButton';
import { useAuth0 } from '@auth0/auth0-react';

// Mock the Auth0 hook
jest.mock('@auth0/auth0-react', () => ({
  useAuth0: jest.fn(),
}));

describe('ProfileButton Component', () => {
  const mockUser = {
    name: 'Test User',
    picture: 'https://example.com/avatar.jpg',
  };

  beforeEach(() => {
    // Clear all mocks before each test
    jest.clearAllMocks();
  });

  it('displays loading state when isLoading is true', () => {
    useAuth0.mockReturnValue({
      isLoading: true,
      isAuthenticated: false,
      user: null,
    });

    const { container } = render(<ProfileButton />);
    
    // Check for the loader by className instead of role
    const loader = container.querySelector('.ui.active.centered.inline.loader');
    expect(loader).toBeInTheDocument();
  });

  it('renders nothing when user is not authenticated', () => {
    useAuth0.mockReturnValue({
      isLoading: false,
      isAuthenticated: false,
      user: null,
    });

    const { container } = render(<ProfileButton />);
    
    // The component should not render anything when not authenticated
    expect(container.firstChild).toBeNull();
  });

  it('renders user info when authenticated', () => {
    useAuth0.mockReturnValue({
      isLoading: false,
      isAuthenticated: true,
      user: mockUser,
    });

    render(<ProfileButton />);
    
    const userImage = screen.getByAltText(`${mockUser.name}'s avatar`);
    const userName = screen.getByText(mockUser.name);
    
    expect(userImage).toBeInTheDocument();
    expect(userImage).toHaveAttribute('src', mockUser.picture);
    expect(userName).toBeInTheDocument();
  });
}); 