import { useState, useEffect } from 'react';
import { useAuth0 } from '@auth0/auth0-react';
import { usersApi } from '../api/usersApi';

export function useUserRegistration() {
  const { isAuthenticated, isLoading, getAccessTokenSilently, user } = useAuth0();
  const [userState, setUserState] = useState({
    userRegistered: false,
    userLoading: true,
    userError: null
  });

  // Effect to check if user exists in our backend and create if not
  useEffect(() => {
    const registerUserIfNeeded = async () => {
      if (isAuthenticated && user) {
        try {
          console.log('Auth state detected, checking if user exists in backend');
          setUserState(prev => ({ ...prev, userLoading: true, userError: null }));
          
          // First check if user already exists in our database
          console.log('Checking if user exists:', user.sub);
          const existingUser = await usersApi.getMe(getAccessTokenSilently);
          console.log('User check result:', existingUser ? 'User exists' : 'User not found');
          
          if (!existingUser) {
            console.log('User not found in database, creating...');
            // User not found, create a new user from Auth0 profile
            const userData = {
              id: user.sub,
              name: user.name,
              email: user.email,
              pictureUrl: user.picture
            };
            
            console.log('Creating user with data:', userData);
            const createdUser = await usersApi.register(userData, getAccessTokenSilently);
            console.log('User registered successfully:', createdUser);
            setUserState({
              userRegistered: true,
              userLoading: false,
              userError: null
            });
          } else {
            console.log('User already exists in database:', existingUser);
            setUserState({
              userRegistered: true,
              userLoading: false,
              userError: null
            });
          }
        } catch (error) {
          console.error('Error during user registration:', error);
          console.error('Error details:', error.message);
          setUserState({
            userRegistered: false,
            userLoading: false,
            userError: error.message || 'Failed to register user'
          });
        }
      } else if (!isLoading && !isAuthenticated) {
        // Not authenticated, not loading
        console.log('User not authenticated or still loading:', { isAuthenticated, isLoading });
        setUserState({
          userRegistered: false,
          userLoading: false,
          userError: null
        });
      }
    };
    
    registerUserIfNeeded();
  }, [isAuthenticated, user, getAccessTokenSilently, isLoading]);

  return userState;
} 