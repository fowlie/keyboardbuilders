import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom'
import { useAuth0 } from '@auth0/auth0-react'
import { Container, Menu, Header } from 'semantic-ui-react'
import { useEffect, useState, createContext } from 'react'
import ProfileButton from './components/ProfileButton'
import LoginButton from './components/LoginButton'
import LogoutButton from './components/LogoutButton'
import KeyboardList from './components/KeyboardList'
import NewKeyboardPage from './pages/NewKeyboardPage'
import ProfilePage from './pages/ProfilePage'
import KeyboardDetailPage from './pages/KeyboardDetailPage'
import { usersApi } from './api/usersApi'

// Create a context to share user registration state
export const UserContext = createContext({
  userRegistered: false,
  userLoading: true,
  userError: null
});

function Home() {
  return (
    <Container text>
      <Header as='h2'>Welcome to Keyboard Builders</Header>
    </Container>
  )
}

function App() {
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

  return (
    <UserContext.Provider value={userState}>
      <Router>
        <Container>
          <Menu fixed='top' inverted>
            <Menu.Item as={Link} to="/" header>
              Keyboard Builders
            </Menu.Item>
            <Menu.Item as={Link} to="/">Home</Menu.Item>
            <Menu.Item as={Link} to="/keyboards">Keyboards</Menu.Item>
            <Menu.Menu position='right'>
              <Menu.Item as={Link} to="/profile">
                <ProfileButton />
              </Menu.Item>
              <Menu.Item>
                {!isAuthenticated && <LoginButton />}
                {isAuthenticated && <LogoutButton />}
              </Menu.Item>
            </Menu.Menu>
          </Menu>

          <Container style={{ marginTop: '7em' }}>
            <Routes>
              <Route path="/" element={<Home />} />
              <Route path="/profile" element={<ProfilePage />} />
              <Route path="/keyboards" element={<KeyboardList />} />
              <Route path="/keyboards/new" element={<NewKeyboardPage />} />
              <Route path="/keyboards/:id" element={<KeyboardDetailPage />} />
            </Routes>
          </Container>
        </Container>
      </Router>
    </UserContext.Provider>
  )
}

export default App
