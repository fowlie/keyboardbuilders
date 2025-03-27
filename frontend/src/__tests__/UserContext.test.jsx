import React, { useContext } from 'react';
import { render, screen, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { UserContext } from '../App';
import { useUserRegistration } from '../hooks/useUserRegistration';

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

// Mock components and hooks
jest.mock('../hooks/useUserRegistration');

// Create a wrapper component that provides the context with controllable values
const ContextProvider = ({ contextValue, children }) => {
  return (
    <UserContext.Provider value={contextValue}>
      {children}
    </UserContext.Provider>
  );
};

describe('UserContext', () => {
  beforeEach(() => {
    // Clear mock implementation
    jest.clearAllMocks();
  });

  it('should provide initial user state values', () => {
    const mockUserState = {
      userRegistered: false,
      userLoading: true,
      userError: null
    };

    render(
      <ContextProvider contextValue={mockUserState}>
        <TestConsumer />
      </ContextProvider>
    );

    expect(screen.getByTestId('user-registered').textContent).toBe('false');
    expect(screen.getByTestId('user-loading').textContent).toBe('true');
    expect(screen.getByTestId('user-error').textContent).toBe('no error');
  });

  it('should update user state when registered', () => {
    const mockUserState = {
      userRegistered: true,
      userLoading: false,
      userError: null
    };

    render(
      <ContextProvider contextValue={mockUserState}>
        <TestConsumer />
      </ContextProvider>
    );

    expect(screen.getByTestId('user-registered').textContent).toBe('true');
    expect(screen.getByTestId('user-loading').textContent).toBe('false');
    expect(screen.getByTestId('user-error').textContent).toBe('no error');
  });

  it('should display error message when there is an error', () => {
    const errorMessage = 'Failed to register user';
    const mockUserState = {
      userRegistered: false,
      userLoading: false,
      userError: errorMessage
    };

    render(
      <ContextProvider contextValue={mockUserState}>
        <TestConsumer />
      </ContextProvider>
    );

    expect(screen.getByTestId('user-registered').textContent).toBe('false');
    expect(screen.getByTestId('user-loading').textContent).toBe('false');
    expect(screen.getByTestId('user-error').textContent).toBe(errorMessage);
  });

  it('should integrate with useUserRegistration hook', () => {
    // Setup mock for the hook
    const mockUserState = {
      userRegistered: true,
      userLoading: false,
      userError: null
    };
    
    useUserRegistration.mockReturnValue(mockUserState);
    
    // Create a simple component that uses the hook
    const TestComponent = () => {
      const userState = useUserRegistration();
      return (
        <ContextProvider contextValue={userState}>
          <TestConsumer />
        </ContextProvider>
      );
    };
    
    render(<TestComponent />);
    
    // Verify the hook was called and context values are correct
    expect(useUserRegistration).toHaveBeenCalled();
    expect(screen.getByTestId('user-registered').textContent).toBe('true');
    expect(screen.getByTestId('user-loading').textContent).toBe('false');
    expect(screen.getByTestId('user-error').textContent).toBe('no error');
  });
}); 