import React, { useState, useEffect } from 'react';
import { Container, Header, Card, Button, Message, Loader, Divider, Label } from 'semantic-ui-react';
import { useAuth0 } from '@auth0/auth0-react';
import { useNavigate } from 'react-router-dom';
import LoginButton from '../components/LoginButton';
import SafeImage from '../components/SafeImage';
import { keyboardsApi } from '../api/keyboardsApi';
import ErrorDisplay from '../components/ErrorDisplay';

const ProfilePage = () => {
    const { user, isAuthenticated, isLoading, getAccessTokenSilently } = useAuth0();
    const [userKeyboards, setUserKeyboards] = useState([]);
    const [keyboardsLoading, setKeyboardsLoading] = useState(false);
    const [keyboardsError, setKeyboardsError] = useState(null);
    const navigate = useNavigate();

    useEffect(() => {
        if (isAuthenticated && user) {
            fetchUserKeyboards();
        }
    }, [isAuthenticated, user]);

    const fetchUserKeyboards = async () => {
        try {
            setKeyboardsLoading(true);
            console.log('Fetching keyboards for user:', user.sub);
            
            // Check if getAccessTokenSilently is available
            if (typeof getAccessTokenSilently !== 'function') {
                console.error('getAccessTokenSilently is not available or not a function');
                setKeyboardsError('Authentication error. Please try logging out and back in.');
                return;
            }

            // First, get the current user's info from the backend to get their UUID
            const token = await getAccessTokenSilently();
            const userResponse = await fetch('http://localhost:8080/api/users/me', {
                headers: {
                    'Authorization': `Bearer ${token}`
                }
            });

            if (!userResponse.ok) {
                throw new Error(`Failed to get user profile (status: ${userResponse.status})`);
            }

            const userData = await userResponse.json();
            console.log('Got user profile with ID:', userData.id);
            
            // Now use the actual UUID from our database to fetch keyboards
            const keyboards = await keyboardsApi.getByUserId(userData.id, getAccessTokenSilently);
            setUserKeyboards(keyboards);
            setKeyboardsError(null);
        } catch (error) {
            console.error('Error fetching user keyboards:', error);
            
            // Provide a more user-friendly error message based on the error
            if (error.message.includes('401') || error.message.includes('403')) {
                setKeyboardsError('Authentication error. Please try logging out and back in.');
            } else if (error.message.includes('404')) {
                setKeyboardsError('The requested resource was not found. Please contact support.');
            } else if (error.message.includes('500')) {
                setKeyboardsError('Server error. Please try again later.');
            } else {
                setKeyboardsError(`Failed to load your keyboards: ${error.message}`);
            }
        } finally {
            setKeyboardsLoading(false);
        }
    };

    const handleKeyboardClick = (id) => {
        navigate(`/keyboards/${id}`);
    };

    const handleAddKeyboard = () => {
        navigate('/keyboards/new');
    };

    if (isLoading) {
        return <Loader active inline='centered' />;
    }

    if (!isAuthenticated) {
        return (
            <Container>
                <Header as='h1'>Profile</Header>
                <Message warning>
                    <Message.Header>Not Logged In</Message.Header>
                    <p>You need to log in to view your profile.</p>
                    <LoginButton />
                </Message>
            </Container>
        );
    }

    return (
        <Container>
            <Header as='h1'>Profile</Header>
            <Card>
                {user.picture && <SafeImage src={user.picture} alt={`${user.name}'s profile picture`} className="ui image" />}
                <Card.Content>
                    <Card.Header>{user.name}</Card.Header>
                    <Card.Meta>{user.email}</Card.Meta>
                </Card.Content>
            </Card>

            <Divider section />
            
            <div style={{ 
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: '1rem'
            }}>
                <Header as='h2'>My Keyboards</Header>
                <Button primary onClick={handleAddKeyboard}>Add New Keyboard</Button>
            </div>

            {keyboardsLoading ? (
                <Loader active>Loading your keyboards...</Loader>
            ) : keyboardsError ? (
                <ErrorDisplay errorMessage={keyboardsError} onRetry={fetchUserKeyboards} />
            ) : (
                <>
                    {userKeyboards.length > 0 ? (
                        <Card.Group>
                            {userKeyboards.map((keyboard) => (
                                <Card
                                    key={keyboard.id}
                                    onClick={() => handleKeyboardClick(keyboard.id)}
                                    style={{ cursor: 'pointer' }}
                                >
                                    <Card.Content>
                                        <Card.Header>{keyboard.name}</Card.Header>
                                        <Card.Meta>
                                            {keyboard.split && <Label color="blue">Split</Label>}
                                            {keyboard.hotswap && <Label color="blue">Hotswap</Label>}
                                        </Card.Meta>
                                    </Card.Content>
                                </Card>
                            ))}
                        </Card.Group>
                    ) : (
                        <Message info>
                            <Message.Header>No Keyboards Found</Message.Header>
                            <p>You haven't submitted any keyboards yet. Click "Add New Keyboard" to get started!</p>
                        </Message>
                    )}
                </>
            )}
        </Container>
    );
};

export default ProfilePage;
