ALTER TABLE clan_applications DROP CONSTRAINT clan_applications_message_check;
ALTER TABLE clan_applications ADD CONSTRAINT clan_applications_message_check CHECK (char_length(message) BETWEEN 0 AND 4000);
