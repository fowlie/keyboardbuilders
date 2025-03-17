import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom'
import { useAuth0 } from '@auth0/auth0-react'
import { Container, Menu, Header } from 'semantic-ui-react'
import Profile from './components/Profile'
import LoginButton from './components/LoginButton'
import LogoutButton from './components/LogoutButton'
import KeyboardList from './components/KeyboardList'
import KeyboardForm from './components/KeyboardForm'

function Home() {
  return (
    <Container text>
      <Header as='h2'>Welcome to Keyboard Builders</Header>
    </Container>
  )
}

function App() {
  const { isAuthenticated } = useAuth0();

  return (
    <Router>
      <Container>
        <Menu fixed='top' inverted>
          <Menu.Item as={Link} to="/" header>
            Keyboard Builders
          </Menu.Item>
          <Menu.Item as={Link} to="/">Home</Menu.Item>
          <Menu.Item as={Link} to="/keyboards">Keyboards</Menu.Item>
          <Menu.Menu position='right'>
            <Menu.Item>
              <Profile />
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
            <Route path="/keyboards" element={<KeyboardList />} />
            <Route path="/keyboards/new" element={<KeyboardForm />} />
          </Routes>
        </Container>
      </Container>
    </Router>
  )
}

export default App
