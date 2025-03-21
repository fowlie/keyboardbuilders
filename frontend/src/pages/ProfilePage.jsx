import React from 'react';
import { Container, Header, Card, Button, Message } from 'semantic-ui-react';
import { useAuth0 } from '@auth0/auth0-react';
import { Loader } from 'semantic-ui-react';
import LoginButton from '../components/LoginButton';
import SafeImage from '../components/SafeImage';

const ProfilePage = () => {
    const { user, isAuthenticated, isLoading } = useAuth0();

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
        </Container>
    );
};

export default ProfilePage;
