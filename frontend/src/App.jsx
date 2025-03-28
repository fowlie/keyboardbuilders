import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom'
import { useAuth0 } from '@auth0/auth0-react'
import { Container, Menu, Header } from 'semantic-ui-react'
import { createContext } from 'react'
import ProfileButton from './components/ProfileButton'
import LoginButton from './components/LoginButton'
import LogoutButton from './components/LogoutButton'
import KeyboardList from './components/KeyboardList'
import NewKeyboardPage from './pages/NewKeyboardPage'
import ProfilePage from './pages/ProfilePage'
import KeyboardDetailPage from './pages/KeyboardDetailPage'
import DevBoardsPage from './pages/DevBoardsPage'
import DevBoardDetailPage from './pages/DevBoardDetailPage'
import NewDevBoardPage from './pages/NewDevBoardPage'
import { useUserRegistration } from './hooks/useUserRegistration'

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
  const { isAuthenticated } = useAuth0();
  const userState = useUserRegistration();

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
            <Menu.Item as={Link} to="/devboards">Dev Boards</Menu.Item>
            <Menu.Menu position='right'>
              {isAuthenticated && (
                <Menu.Item as={Link} to="/profile">
                  <ProfileButton />
                </Menu.Item>
              )}
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
              <Route path="/devboards" element={<DevBoardsPage />} />
              <Route path="/devboards/new" element={<NewDevBoardPage />} />
              <Route path="/devboards/:id" element={<DevBoardDetailPage />} />
            </Routes>
          </Container>
        </Container>
      </Router>
    </UserContext.Provider>
  )
}

export default App
