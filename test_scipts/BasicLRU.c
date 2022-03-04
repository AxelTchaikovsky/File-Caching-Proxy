#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>


int main() {
    int fd = 0;
    printf("fd = %d\n", fd = open("A", O_RDONLY));
    char *read_buf;
    ssize_t read_bytes = read(fd, read_buf, 10);
    printf("read: %ld %s", read_bytes, read_buf);
    sleep(30);
    close(fd);
}